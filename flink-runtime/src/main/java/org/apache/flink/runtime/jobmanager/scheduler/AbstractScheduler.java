package org.apache.flink.runtime.jobmanager.scheduler;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.SlotProfile;
import org.apache.flink.runtime.executiongraph.ExecutionVertex;
import org.apache.flink.runtime.instance.*;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobmaster.LogicalSlot;
import org.apache.flink.runtime.jobmaster.SlotRequestId;
import org.apache.flink.runtime.jobmaster.slotpool.SlotProvider;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

public abstract class AbstractScheduler implements InstanceListener, SlotAvailabilityListener, SlotProvider {
	/** Scheduler-wide logger */
	protected static final Logger LOG = LoggerFactory.getLogger(AbstractScheduler.class);
	/** The Executor which is used to execute newSlotAvailable futures. */
	protected final Executor executor;
	/** All modifications to the scheduler structures are performed under a global scheduler lock */
	protected final Object globalLock = new Object();
	/** All instances that the scheduler can deploy to */
	private final Set<Instance> allInstances = new HashSet<Instance>();
	/** All instances by hostname */
	private final HashMap<String, Set<Instance>> allInstancesByHost = new HashMap<String, Set<Instance>>();
	/** All instances that still have available resources */
	private final Map<ResourceID, Instance> instancesWithAvailableResources = new LinkedHashMap<>();
	/** All tasks pending to be scheduled */
	protected final Queue<QueuedTask> taskQueue = new ArrayDeque<QueuedTask>();
	private final BlockingQueue<Instance> newlyAvailableInstances = new LinkedBlockingQueue<Instance>();
	/** The number of slot allocations that had no location preference */
	private int unconstrainedAssignments;
	/** The number of slot allocations where locality could be respected */
	private int localizedAssignments;
	/** The number of slot allocations where locality could not be respected */
	private int nonLocalizedAssignments;

	protected AbstractScheduler(Executor executor) {
		this.executor = Preconditions.checkNotNull(executor);
	}

	protected static String getHostnamesFromInstances(Iterable<TaskManagerLocation> locations) {
		StringBuilder bld = new StringBuilder();

		boolean successive = false;
		for (TaskManagerLocation loc : locations) {
			if (successive) {
				bld.append(", ");
			} else {
				successive = true;
			}
			bld.append(loc.getHostname());
		}

		return bld.toString();
	}

	/**
	 * Shuts the scheduler down. After shut down no more tasks can be added to the scheduler.
	 */
	public void shutdown() {
		synchronized (globalLock) {
			for (Instance i : allInstances) {
				i.removeSlotListener();
				i.cancelAndReleaseAllSlots();
			}
			allInstances.clear();
			allInstancesByHost.clear();
			instancesWithAvailableResources.clear();
			taskQueue.clear();
		}
	}

	@Override
	public abstract CompletableFuture<LogicalSlot> allocateSlot(
		SlotRequestId slotRequestId,
		ScheduledUnit task,
		boolean allowQueued,
		SlotProfile slotProfile,
		Time allocationTimeout);

	@Override
	public abstract CompletableFuture<Acknowledge> cancelSlotRequest(SlotRequestId slotRequestId, @Nullable SlotSharingGroupId slotSharingGroupId, Throwable cause);



	/**
	 * Gets a suitable instance to schedule the vertex execution to.
	 * <p>
	 * NOTE: This method does is not thread-safe, it needs to be synchronized by the caller.
	 *
	 * @param vertex The task to run.
	 * @return The instance to run the vertex on, it {@code null}, if no instance is available.
	 */
	protected SimpleSlot getFreeSlotForTask(ExecutionVertex vertex,
											Iterable<TaskManagerLocation> requestedLocations,
											boolean localOnly) {
		// we need potentially to loop multiple times, because there may be false positives
		// in the set-with-available-instances
		while (true) {
			Pair<Instance, Locality> instanceLocalityPair = findInstance(requestedLocations, localOnly);

			if (instanceLocalityPair == null){
				return null;
			}

			Instance instanceToUse = instanceLocalityPair.getLeft();
			Locality locality = instanceLocalityPair.getRight();

			try {
				SimpleSlot slot = instanceToUse.allocateSimpleSlot();

				// if the instance has further available slots, re-add it to the set of available resources.
				if (instanceToUse.hasResourcesAvailable()) {
					this.instancesWithAvailableResources.put(instanceToUse.getTaskManagerID(), instanceToUse);
				}

				if (slot != null) {
					slot.setLocality(locality);
					return slot;
				}
			}
			catch (InstanceDiedException e) {
				// the instance died it has not yet been propagated to this scheduler
				// remove the instance from the set of available instances
				removeInstance(instanceToUse);
			}

			// if we failed to get a slot, fall through the loop
		}
	}

	/**
	 * Tries to allocate a new slot for a vertex that is part of a slot sharing group. If one
	 * of the instances has a slot available, the method will allocate it as a shared slot, add that
	 * shared slot to the sharing group, and allocate a simple slot from that shared slot.
	 *
	 * <p>This method will try to allocate a slot from one of the local instances, and fall back to
	 * non-local instances, if permitted.</p>
	 *
	 * @param vertex The vertex to allocate the slot for.
	 * @param requestedLocations The locations that are considered local. May be null or empty, if the
	 *                           vertex has no location preferences.
	 * @param groupAssignment The slot sharing group of the vertex. Mandatory parameter.
	 * @param constraint The co-location constraint of the vertex. May be null.
	 * @param localOnly Flag to indicate if non-local choices are acceptable.
	 *
	 * @return A sub-slot for the given vertex, or {@code null}, if no slot is available.
	 */
	protected SimpleSlot getNewSlotForSharingGroup(ExecutionVertex vertex,
													Iterable<TaskManagerLocation> requestedLocations,
													SlotSharingGroupAssignment groupAssignment,
													CoLocationConstraint constraint,
													boolean localOnly)
	{
		// we need potentially to loop multiple times, because there may be false positives
		// in the set-with-available-instances
		while (true) {
			Pair<Instance, Locality> instanceLocalityPair = findInstance(requestedLocations, localOnly);

			if (instanceLocalityPair == null) {
				// nothing is available
				return null;
			}

			final Instance instanceToUse = instanceLocalityPair.getLeft();
			final Locality locality = instanceLocalityPair.getRight();

			try {
				JobVertexID groupID = vertex.getJobvertexId();

				// allocate a shared slot from the instance
				SharedSlot sharedSlot = instanceToUse.allocateSharedSlot(groupAssignment);

				// if the instance has further available slots, re-add it to the set of available resources.
				if (instanceToUse.hasResourcesAvailable()) {
					this.instancesWithAvailableResources.put(instanceToUse.getTaskManagerID(), instanceToUse);
				}

				if (sharedSlot != null) {
					// add the shared slot to the assignment group and allocate a sub-slot
					SimpleSlot slot = constraint == null ?
							groupAssignment.addSharedSlotAndAllocateSubSlot(sharedSlot, locality, groupID) :
							groupAssignment.addSharedSlotAndAllocateSubSlot(sharedSlot, locality, constraint);

					if (slot != null) {
						return slot;
					}
					else {
						// could not add and allocate the sub-slot, so release shared slot
						sharedSlot.releaseSlot(new FlinkException("Could not allocate sub-slot."));
					}
				}
			}
			catch (InstanceDiedException e) {
				// the instance died it has not yet been propagated to this scheduler
				// remove the instance from the set of available instances
				removeInstance(instanceToUse);
			}

			// if we failed to get a slot, fall through the loop
		}
	}

	/**
	 * Tries to find a requested instance with available resources. If no such instance is available it will return a non-
	 * local instance. If no such instance exists (all slots occupied), then return null.
	 *
	 * <p><b>NOTE:</b> This method is not thread-safe, it needs to be synchronized by the caller.</p>
	 *
	 * @param requestedLocations The list of preferred instances. May be null or empty, which indicates that
	 *                           no locality preference exists.
	 * @param localOnly Flag to indicate whether only one of the exact local instances can be chosen.
	 */
	private Pair<Instance, Locality> findInstance(Iterable<TaskManagerLocation> requestedLocations, boolean localOnly) {

		// drain the queue of newly available instances
		while (this.newlyAvailableInstances.size() > 0) {
			Instance queuedInstance = this.newlyAvailableInstances.poll();
			if (queuedInstance != null) {
				this.instancesWithAvailableResources.put(queuedInstance.getTaskManagerID(), queuedInstance);
			}
		}

		// if nothing is available at all, return null
		if (this.instancesWithAvailableResources.isEmpty()) {
			return null;
		}

		Iterator<TaskManagerLocation> locations = requestedLocations == null ? null : requestedLocations.iterator();

		if (locations != null && locations.hasNext()) {
			// we have a locality preference

			while (locations.hasNext()) {
				TaskManagerLocation location = locations.next();
				if (location != null) {
					Instance instance = instancesWithAvailableResources.remove(location.getResourceID());
					if (instance != null) {
						return new ImmutablePair<Instance, Locality>(instance, Locality.LOCAL);
					}
				}
			}

			// no local instance available
			if (localOnly) {
				return null;
			}
			else {
				// take the first instance from the instances with resources
				Iterator<Instance> instances = instancesWithAvailableResources.values().iterator();
				Instance instanceToUse = instances.next();
				instances.remove();

				return new ImmutablePair<>(instanceToUse, Locality.NON_LOCAL);
			}
		}
		else {
			// no location preference, so use some instance
			Iterator<Instance> instances = instancesWithAvailableResources.values().iterator();
			Instance instanceToUse = instances.next();
			instances.remove();

			return new ImmutablePair<>(instanceToUse, Locality.UNCONSTRAINED);
		}
	}

	@Override
	public void newSlotAvailable(final Instance instance) {

		// WARNING: The asynchrony here is necessary, because  we cannot guarantee the order
		// of lock acquisition (global scheduler, instance) and otherwise lead to potential deadlocks:
		//
		// -> The scheduler needs to grab them (1) global scheduler lock
		//                                     (2) slot/instance lock
		// -> The slot releasing grabs (1) slot/instance (for releasing) and
		//                             (2) scheduler (to check whether to take a new task item
		//
		// that leads with a high probability to deadlocks, when scheduling fast

		newlyAvailableInstances.add(instance);

		executor.execute(new Runnable() {
			@Override
			public void run() {
				handleNewSlot();
			}
		});
	}

	private void handleNewSlot() {

		synchronized (globalLock) {
			Instance instance = this.newlyAvailableInstances.poll();
			if (instance == null || !instance.hasResourcesAvailable()) {
				// someone else took it
				return;
			}

			QueuedTask queued = taskQueue.peek();

			// the slot was properly released, we can allocate a new one from that instance

			if (queued != null) {
				ScheduledUnit task = queued.getTask();
				ExecutionVertex vertex = task.getTaskToExecute().getVertex();

				try {
					SimpleSlot newSlot = instance.allocateSimpleSlot();
					if (newSlot != null) {

						// success, remove from the task queue and notify the future
						taskQueue.poll();
						if (queued.getFuture() != null) {
							try {
								queued.getFuture().complete(newSlot);
							}
							catch (Throwable t) {
								LOG.error("Error calling allocation future for task " + vertex.getTaskNameWithSubtaskIndex(), t);
								task.getTaskToExecute().fail(t);
							}
						}
					}
				}
				catch (InstanceDiedException e) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("Instance " + instance + " was marked dead asynchronously.");
					}

					removeInstance(instance);
				}
			}
			else {
				this.instancesWithAvailableResources.put(instance.getTaskManagerID(), instance);
			}
		}
	}

	protected void updateLocalityCounters(SimpleSlot slot, ExecutionVertex vertex) {
		Locality locality = slot.getLocality();

		switch (locality) {
		case UNCONSTRAINED:
			this.unconstrainedAssignments++;
			break;
		case LOCAL:
			this.localizedAssignments++;
			break;
		case NON_LOCAL:
			this.nonLocalizedAssignments++;
			break;
		default:
			throw new RuntimeException(locality.name());
		}

		if (LOG.isDebugEnabled()) {
			switch (locality) {
				case UNCONSTRAINED:
					LOG.debug("Unconstrained assignment: " + vertex.getTaskNameWithSubtaskIndex() + " --> " + slot);
					break;
				case LOCAL:
					LOG.debug("Local assignment: " + vertex.getTaskNameWithSubtaskIndex() + " --> " + slot);
					break;
				case NON_LOCAL:
					LOG.debug("Non-local assignment: " + vertex.getTaskNameWithSubtaskIndex() + " --> " + slot);
					break;
			}
		}
	}

	@Override
	public void newInstanceAvailable(Instance instance) {
		if (instance == null) {
			throw new IllegalArgumentException();
		}
		if (instance.getNumberOfAvailableSlots() <= 0) {
			throw new IllegalArgumentException("The given instance has no resources.");
		}
		if (!instance.isAlive()) {
			throw new IllegalArgumentException("The instance is not alive.");
		}

		// synchronize globally for instance changes
		synchronized (this.globalLock) {

			// check we do not already use this instance
			if (!this.allInstances.add(instance)) {
				throw new IllegalArgumentException("The instance is already contained.");
			}

			try {
				// make sure we get notifications about slots becoming available
				instance.setSlotAvailabilityListener(this);

				// store the instance in the by-host-lookup
				String instanceHostName = instance.getTaskManagerLocation().getHostname();
				Set<Instance> instanceSet = allInstancesByHost.get(instanceHostName);
				if (instanceSet == null) {
					instanceSet = new HashSet<Instance>();
					allInstancesByHost.put(instanceHostName, instanceSet);
				}
				instanceSet.add(instance);

				// add it to the available resources and let potential waiters know
				this.instancesWithAvailableResources.put(instance.getTaskManagerID(), instance);

				// add all slots as available
				for (int i = 0; i < instance.getNumberOfAvailableSlots(); i++) {
					newSlotAvailable(instance);
				}
			}
			catch (Throwable t) {
				LOG.error("Scheduler could not add new instance " + instance, t);
				removeInstance(instance);
			}
		}
	}

	@Override
	public void instanceDied(Instance instance) {
		if (instance == null) {
			throw new IllegalArgumentException();
		}

		instance.markDead();

		// we only remove the instance from the pools, we do not care about the
		synchronized (this.globalLock) {
			// the instance must not be available anywhere any more
			removeInstance(instance);
		}
	}

	private void removeInstance(Instance instance) {
		if (instance == null) {
			throw new NullPointerException();
		}

		allInstances.remove(instance);
		instancesWithAvailableResources.remove(instance.getTaskManagerID());

		String instanceHostName = instance.getTaskManagerLocation().getHostname();
		Set<Instance> instanceSet = allInstancesByHost.get(instanceHostName);
		if (instanceSet != null) {
			instanceSet.remove(instance);
			if (instanceSet.isEmpty()) {
				allInstancesByHost.remove(instanceHostName);
			}
		}
	}

	/**
	 *
	 * NOTE: In the presence of multi-threaded operations, this number may be inexact.
	 *
	 * @return The number of empty slots, for tasks.
	 */
	public int getNumberOfAvailableSlots() {
		int count = 0;

		synchronized (globalLock) {
			processNewlyAvailableInstances();

			for (Instance instance : instancesWithAvailableResources.values()) {
				count += instance.getNumberOfAvailableSlots();
			}
		}

		return count;
	}

	public int getTotalNumberOfSlots() {
		int count = 0;

		synchronized (globalLock) {
			for (Instance instance : allInstances) {
				if (instance.isAlive()) {
					count += instance.getTotalNumberOfSlots();
				}
			}
		}

		return count;
	}

	public int getNumberOfAvailableInstances() {
		int numberAvailableInstances = 0;
		synchronized (this.globalLock) {
			for (Instance instance: allInstances ){
				if (instance.isAlive()){
					numberAvailableInstances++;
				}
			}
		}

		return numberAvailableInstances;
	}

	public int getNumberOfInstancesWithAvailableSlots() {
		synchronized (globalLock) {
			processNewlyAvailableInstances();

			return instancesWithAvailableResources.size();
		}
	}

	public Map<String, List<Instance>> getInstancesByHost() {
		synchronized (globalLock) {
			HashMap<String, List<Instance>> copy = new HashMap<String, List<Instance>>();

			for (Map.Entry<String, Set<Instance>> entry : allInstancesByHost.entrySet()) {
				copy.put(entry.getKey(), new ArrayList<Instance>(entry.getValue()));
			}
			return copy;
		}
	}

	public int getNumberOfUnconstrainedAssignments() {
		return unconstrainedAssignments;
	}

	public int getNumberOfLocalizedAssignments() {
		return localizedAssignments;
	}

	public int getNumberOfNonLocalizedAssignments() {
		return nonLocalizedAssignments;
	}

	private void processNewlyAvailableInstances() {
		synchronized (globalLock) {
			Instance instance;

			while ((instance = newlyAvailableInstances.poll()) != null) {
				if (instance.hasResourcesAvailable()) {
					instancesWithAvailableResources.put(instance.getTaskManagerID(), instance);
				}
			}
		}
	}

	@VisibleForTesting
	@Nullable
	public Instance getInstance(ResourceID resourceId) {
		for (Instance instance : allInstances) {
			if (Objects.equals(resourceId, instance.getTaskManagerID())) {
				return instance;
			}
		}
		return null;
	}

	/**
	 * An entry in the queue of schedule requests. Contains the task to be scheduled and
	 * the future that tracks the completion.
	 */
	protected static final class QueuedTask {

		private final ScheduledUnit task;

		private final CompletableFuture<LogicalSlot> future;


		public QueuedTask(ScheduledUnit task, CompletableFuture<LogicalSlot> future) {
			this.task = task;
			this.future = future;
		}

		public ScheduledUnit getTask() {
			return task;
		}

		public CompletableFuture<LogicalSlot> getFuture() {
			return future;
		}
	}
}