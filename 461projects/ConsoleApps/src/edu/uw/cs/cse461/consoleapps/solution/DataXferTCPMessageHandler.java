package edu.uw.cs.cse461.consoleapps.solution;

import edu.uw.cs.cse461.consoleapps.PingInterface;
import edu.uw.cs.cse461.net.base.NetLoadable.NetLoadableConsoleApp;

public class DataXferTCPMessageHandler extends NetLoadableConsoleApp implements
		PingInterface {

	public DataXferTCPMessageHandler() {
		super("dataxfertcpmessagehandler");
	}
	
	@Override
	public void run() throws Exception {
		System.out.println("DataXferTCPMessageHandler.run() called");
	}

}
