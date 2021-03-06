package testingFrameworks;

import org.apache.flink.runtime.clusterframework.types.GeoLocation;
import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.executiongraph.OptimisationModelParameters;
import org.apache.flink.runtime.instance.Instance;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.ScheduleMode;
import org.apache.flink.runtime.jobmanager.scheduler.GeoScheduler;
import org.apache.flink.runtime.jobmanager.scheduler.Scheduler;
import org.apache.flink.runtime.jobmanager.scheduler.StaticBandwidthProvider;
import org.apache.flink.runtime.testingUtils.TestingUtils;
import org.apache.flink.types.TwoKeysMultiMap;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spies.SchedulingDecisionSpy;
import spies.SpyableFlinkScheduler;
import spies.SpyableGeoScheduler;
import spies.SpyableScheduler;
import testOutputWriter.TestOutputImpl;
import testOutputWriter.TestOutputWriter;
import writableTypes.TestInstanceSet;
import writableTypes.TestJobGraph;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.runtime.jobmanager.scheduler.SchedulerTestUtils.makeExecutionGraph;
import static testingFrameworks.SchedulingTestFrameworkUtils.writeTestOutcome;

/**
 * This tests logs the slot usages after scheduling a {@link TestJobGraph}.
 */
@RunWith(Parameterized.class)
@Ignore
public abstract class JobGraphSchedulingTestFramework {

	private final static Logger log = LoggerFactory.getLogger(JobGraphSchedulingTestFramework.class);

	private final TestOutputWriter<TestOutputImpl> writer = new TestOutputWriter<>(this.getClass().getSimpleName() + ".csv");

	@Parameterized.Parameters(name = "geoScheduling?: {0}")
	public static Collection<Object[]> data() {
		return Arrays.asList(new Object[][]{
			{true}, {false}
		});
	}

	@Parameterized.Parameter(0)
	public boolean isGeoScheduling;

	public Scheduler scheduler;

	private ExecutionGraph executionGraph;
	private SchedulingDecisionSpy spy;

	/**
	 * @return the JobGraph to schedule
	 */
	protected abstract TestJobGraph jobGraph();

	/**
	 * @return the Set of Instance to schedule on
	 */
	protected abstract TestInstanceSet instanceSet();

	/**
	 * @return the vertices that are already placed in a geolocation
	 */
	protected Map<JobVertex, GeoLocation> placedVertices() {
		return new HashMap<>();
	}

	@Before
	public void setup() {

		LogManager.getRootLogger().setLevel(Level.OFF);
		if(isGeoScheduling) {
			scheduler = new SpyableGeoScheduler(TestingUtils.defaultExecutor());
		} else {
			scheduler = new SpyableFlinkScheduler(TestingUtils.defaultExecutor());
		}

		SpyableScheduler spyableScheduler = ((SpyableScheduler) scheduler);

		for (Instance i : instanceSet().getInstances()) {
			scheduler.newInstanceAvailable(i);
		}

		jobGraph().getJobGraph().setOptimisationModelParameters(new OptimisationModelParameters(0.5, 0.5, 10, true));


		spy = new SchedulingDecisionSpy();
		spy.addPlacedVertices(placedVertices());
		spyableScheduler.addSchedulingDecisionSpy(spy);
	}

	@Test
	public void test() throws Exception {
		if(scheduler instanceof GeoScheduler) {
			jobGraph().getJobGraph().solveOptimisationModel(new StaticBandwidthProvider(new TwoKeysMultiMap<>()), ((GeoScheduler) scheduler).calculateAvailableSlotsByGeoLocation());
		}

		executionGraph = makeExecutionGraph(
			jobGraph().getJobGraph(),
			scheduler,
			placedVertices());


		executionGraph.setScheduleMode(ScheduleMode.EAGER);



		executionGraph.scheduleForExecution();

	}

	@After
	public void teardown() {
		writeTestOutcome(executionGraph.getJobID(), spy, scheduler, writer, jobGraph().getClassNameString(), instanceSet().getClassNameString());
	}


}
