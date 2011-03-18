//Copyright 2007 Google Inc.

//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at

//http://www.apache.org/licenses/LICENSE-2.0

//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.

package com.google.enterprise.connector.sharepoint.spiimpl;

import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.enterprise.connector.sharepoint.client.SPConstants;
import com.google.enterprise.connector.sharepoint.client.SharepointClient;
import com.google.enterprise.connector.sharepoint.client.SharepointClientContext;
import com.google.enterprise.connector.sharepoint.state.GlobalState;
import com.google.enterprise.connector.sharepoint.state.WebState;
import com.google.enterprise.connector.spi.DocumentList;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.TraversalManager;

/**
 * This class is an implementation of the TraversalManager from the spi.
 * All the traversal based logic is invoked through this class.
 * @author amit_kagrawal
 */

public class SharepointTraversalManager implements TraversalManager {
	private final  Logger LOGGER = Logger.getLogger(SharepointTraversalManager.class.getName());
	private SharepointClientContext sharepointClientContext;
	private SharepointClientContext sharepointClientContextOriginal=null;
	protected GlobalState globalState; // not private, so the unittest can see it
	private int hint = -1;

	/**
	 * constructor.
	 * @param inConnector The instance of SharePoint connector for which traversal is to be done 
	 * @param inSharepointClientContext The context attached with the connector instances
	 * @throws RepositoryException
	 */
	public SharepointTraversalManager(final SharepointConnector inConnector,final SharepointClientContext inSharepointClientContext) 
	throws RepositoryException {
		if(inConnector == null){
			throw new SharepointException("Can not initialize traversal manager becasue SharePointConnector object is null");
		}
		if(inSharepointClientContext == null){
			throw new SharepointException("Can not initialize traversal manager becasue SharePointClientContext object is null");
		}		
		try{
			LOGGER.config("SharepointTraversalManager: " + inSharepointClientContext.getSiteURL() + ", " + inSharepointClientContext.getGoogleConnectorWorkDir());
			sharepointClientContext = inSharepointClientContext;
			sharepointClientContextOriginal = (SharepointClientContext) inSharepointClientContext.clone();
			globalState = new GlobalState(inSharepointClientContext.getGoogleConnectorWorkDir(), inSharepointClientContext.getFeedType());
			globalState.loadState();				
		}catch (final Exception e) {
			LOGGER.log(Level.WARNING,e.getMessage());
			throw new SharepointException(e);
		}
		LOGGER.info("SharepointTraversalManager(SharepointConnector inConnector,SharepointClientContext inSharepointClientContext)");
	}
	
	/**
	 * Starts the traversal from a checkpoint specified by CM. 
	 * The connector has returned this checkpoint information to the CM at the completion of last vtraversal.
	 * 
	 * Though, SharePoint Connector does not really make use of this checkpoint information for resuming the travesal.
	 * Instead, it uses the state file for this purpose. State file implementation is specific to the connector and CM is unaware of this.
	 * 
	 *  @param checkpoint Not really used by the SharePoint connector
	 */
	public DocumentList resumeTraversal(final String checkpoint) throws RepositoryException {
		LOGGER.info("resumeTraversal, checkpoint received: "+checkpoint);
		// If feed type has been changed after the last traversal cycle. Let's start a full recrawl
		if((globalState.getFeedType() == null) || !globalState.getFeedType().equalsIgnoreCase(sharepointClientContext.getFeedType())) {
			LOGGER.log(Level.INFO, "feedType updated. initiating a full recrawl. ");
			return startTraversal();			
		} else {
			return doTraversal();
		}
	}

	/**
	 * Sets the batch hint which declares a threashold on the number of documents that should be sent per traversal
	 * @see com.google.enterprise.connector.spi.TraversalManager
	 * #setBatchHint(int)
	 */
	public void setBatchHint(final int hintNew) throws RepositoryException {		
		hint = hintNew;
		LOGGER.info("BatchHint Set to [ " + hintNew + " ] ");		
	}

	/**
	 * To start a full crawl. ignoring any checkpoint information
	 * @see com.google.enterprise.connector.spi.TraversalManager
	 * #startTraversal()
	 */ 
	public DocumentList startTraversal() throws RepositoryException {
		LOGGER.info("startTraversal()");
		//delete the global state.. to simulate full crawl
		globalState=null;
		final String workDir = sharepointClientContext.getGoogleConnectorWorkDir();
		GlobalState.forgetState(workDir);
		sharepointClientContext.clearExcludedURLLogs();
		globalState = new GlobalState(sharepointClientContext.getGoogleConnectorWorkDir(), sharepointClientContext.getFeedType());
		return doTraversal();		
	}

	/**
	 * Private routine that actually does the traversal. If no docs are found
	 * in the first sharepointClient.traverse() call, we go back to Sharepoint
	 * and fetch a new set of stuff.
	 * @return PropertyMapList
	 * @throws RepositoryException
	 */
	private DocumentList doTraversal() throws RepositoryException {
 		LOGGER.config("doTraversal()");
		
 		if(hint == -1){
			LOGGER.severe("Batch hint is -1");
			throw new SharepointException("Batch hint is -1");
		}
 		if(sharepointClientContext == null){
			LOGGER.severe("SharepointClientContext is null");
			throw new SharepointException("SharepointClientContext is null");
		}
 		
		LOGGER.config("sharepointClientContext.feedType [ "+sharepointClientContext.getFeedType()+" ]");
		if(!SPConstants.CONTENT_FEED.equalsIgnoreCase(sharepointClientContext.getFeedType())
			&& !SPConstants.METADATA_URL_FEED.equalsIgnoreCase(sharepointClientContext.getFeedType())){
			LOGGER.severe("Aborting Traversal. Invalid Feed Type.");
			return null;
		}
		
		final SharepointClient sharepointClient = new SharepointClient(sharepointClientContext);
		
		sharepointClientContext.setBatchHint(hint);
		SPDocumentList rsAll = null;		
		
		// First, get the documents discovered in the previous crawl cycle.			 
		rsAll = traverse(sharepointClient);
		if((rsAll != null) && (rsAll.size() > 0)) {
			LOGGER.info("Traversal returned " + rsAll.size() + " documents discovered in the previous crawl cycle(s).");
		} else {
			LOGGER.info("No documents to be sent from the previous crawl cycle. Starting recrawl...");
			try {
				sharepointClient.updateGlobalState(globalState);
			} catch(final Exception e) {
				LOGGER.log(Level.SEVERE, "Exception while updating global state.... ", e);
			} catch (final Throwable t) {
				LOGGER.log(Level.SEVERE, "Error while updating global state.... ", t);
			}
			final SPDocumentList rs = traverse(sharepointClient);
			if(rs != null) {
				LOGGER.info("Traversal returned " + rs.size() + " documents discovered in the current crawl cycle.");
				if(rsAll == null) {
					rsAll = rs;
				} else {
					rsAll.addAll(rs);
				}
			} else {
				LOGGER.info("No documents to be sent from the current crawl cycle.");
			}
			if(sharepointClient.isDoCrawl()) {
				if(null == rsAll || rsAll.size() == 0) {
					String tmpStoredWeb = globalState.getLastCrawledWebID();
					if(null != tmpStoredWeb && tmpStoredWeb.trim().length() > 0) {
						LOGGER.log(Level.INFO, "Setting LastCrawledWebStateID and LastCrawledListStateID as null and updating the state file to reflect that a full crawl has completed...");
						globalState.setLastCrawledWebID(null);
						globalState.setLastCrawledListID(null);
						globalState.saveState();
					}
				}
			}
		}
		
		if(sharepointClientContextOriginal != null){
			LOGGER.log(Level.FINEST,"Resetting the sharepointClientContext to the original sharepointClientContext at the end of traversal.");
			sharepointClientContext = (SharepointClientContext) sharepointClientContextOriginal.clone();
		}
		if(rsAll != null){
			LOGGER.info("Traversal returned ["+rsAll.size()+"] documents");
		}else{
			LOGGER.info("Traversal returned [0] documents");
		}
			
		return rsAll;           
	}
	
	/**
	 * 
	 * @param sharepointClient
	 * @return {@link SPDocumentList}
	 */
	private SPDocumentList traverse(final SharepointClient sharepointClient) {
		final String lastWeb = globalState.getLastCrawledWebID();
		if(null==lastWeb){
			globalState.setCurrentWeb(null);
		}else{
			final WebState ws = globalState.lookupWeb(lastWeb,sharepointClientContext);
			globalState.setCurrentWeb(ws);
		}
		SPDocumentList rsAll = null;
		int sizeSoFar = 0;
		for (final Iterator iter = globalState.getCircularIterator(); iter.hasNext() && (sizeSoFar < hint);) {
			final WebState webState = (WebState) iter.next();
			globalState.setCurrentWeb(webState);
			SPDocumentList rs = null;
			try {
				rs = sharepointClient.traverse(globalState,webState, hint);
			} catch(final Exception e) {
				LOGGER.log(Level.WARNING, "Exception occured while traversing web URL [ " +  webState.getWebUrl() + " ] ");
			} catch (final Throwable t) {
				LOGGER.log(Level.WARNING, "Error occured while traversing web URL [ " +  webState.getWebUrl() + " ] ");
			}
			if ((rs!=null) && (rs.size() > 0)) {
				LOGGER.log(Level.INFO, rs.size() + " document(s) to be sent from web URL [ " + webState.getWebUrl() + " ]. ");
				if(rsAll==null){
					rsAll = rs;	
				}else{
					rsAll.addAll(rs);					
				}
				sizeSoFar = rsAll.size();
			} else {
				LOGGER.log(Level.INFO, "No documents to be sent from web URL [ " + webState.getWebUrl() + " ]. ");
			}
		}
		return rsAll;
	}
}