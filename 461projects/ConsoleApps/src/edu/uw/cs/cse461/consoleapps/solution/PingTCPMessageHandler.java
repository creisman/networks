package edu.uw.cs.cse461.consoleapps.solution;

import edu.uw.cs.cse461.consoleapps.PingInterface;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp;

public class PingTCPMessageHandler extends NetLoadableConsoleApp implements
		PingInterface {

	public PingTCPMessageHandler() {
		super("ping");
	}

	@Override
	public void run() throws Exception {
		System.out.println("PingTCPMessageHandler.run() called");
	}

}
