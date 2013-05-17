package edu.uw.cs.cse461.service;

import edu.uw.cs.cse461.net.base.NetLoadableInterface.NetLoadableServiceInterface;

public class DataXferTCPMessageHandlerService extends DataXferServiceBase
		implements NetLoadableServiceInterface {

	public DataXferTCPMessageHandlerService() {
		super("dataxfer");
		
		System.out.println("DataXferTCPMessageHandlerService constructor called");
	}

}
