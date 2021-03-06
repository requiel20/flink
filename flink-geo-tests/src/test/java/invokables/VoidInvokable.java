package invokables;

import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;

public class VoidInvokable extends AbstractInvokable {
	/**
	 * Create an Invokable task and set its environment.
	 *
	 * @param environment The environment assigned to this invokable.
	 */
	public VoidInvokable(Environment environment) {
		super(environment);
	}

	@Override
	public void invoke() throws Exception {
	}
}
