// Copyright 2007 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.sharepoint.spiimpl;

import com.google.enterprise.connector.adgroups.AdGroupsTraversalManager;
import com.google.enterprise.connector.sharepoint.TestConfiguration;
import com.google.enterprise.connector.sharepoint.client.SPConstants;
import com.google.enterprise.connector.sharepoint.client.SharepointClientContext;
import com.google.enterprise.connector.sharepoint.social.SharepointSocialClientContext;
import com.google.enterprise.connector.sharepoint.social.SharepointSocialTraversalManager;
import com.google.enterprise.connector.spi.DocumentList;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.Session;

import java.util.Collections;
import java.util.List;

import junit.framework.TestCase;

public class SharePointTraversalManagerTest extends TestCase {

  SharepointClientContext sharepointClientContext;
  SharepointTraversalManager travMan;

  protected void setUp() throws Exception {
    System.out.println("\n...Setting Up...");
    System.out.println("Initializing SharepointClientContext ...");
    this.sharepointClientContext = TestConfiguration.initContext();

    assertNotNull(this.sharepointClientContext);
    sharepointClientContext.setIncluded_metadata(TestConfiguration.whiteList);
    sharepointClientContext.setExcluded_metadata(TestConfiguration.blackList);

    System.out.println("Initializing SharepointConnector ...");
    final SharepointConnector connector = TestConfiguration.getConnectorInstance();
    connector.setFQDNConversion(TestConfiguration.FQDNflag);
    System.out.println("Initializing SharepointTraversalManager ...");
    final SharepointSocialClientContext social = 
        TestConfiguration.initSocialContext(this.sharepointClientContext);

    this.travMan = new SharepointTraversalManager(connector,
        this.sharepointClientContext, 
        new SharepointSocialTraversalManager(social), null);
    this.travMan.setBatchHint(100);
  }

  public void testStartTraversal() {
    System.out.println("Testing startTraversal()...");
    try {
      final DocumentList docList = this.travMan.startTraversal();
      assertNotNull(docList);
      System.out.println("[ startTraversal() ] Test Passed.");
    } catch (final RepositoryException re) {
      System.out.println(re);
      System.out.println("[ startTraversal() ] Test Failed.");
    }
  }

  public void testResumeTraversal() {
    System.out.println("Testing resumeTraversal()...");
    try {
      final DocumentList docList = this.travMan.resumeTraversal("SharePoint");
      assertNotNull(docList);
      System.out.println("[ resumeTraversal() ] Test Passed.");
    } catch (final RepositoryException re) {
      System.out.println(re);
      System.out.println("[ resumeTraversal() ] Test Failed.");
    }
  }
  
  public void testBatchTimeoutandCheckpoint() throws RepositoryException {
    SharepointTraversalManager manager = this.travMan;
    sharepointClientContext.setSocialOption(
        SharepointConnector.SocialOption.NO);
    DocumentList initial = manager.startTraversal();
    List<SPDocument> pass1 = ((SPDocumentList) initial).getDocuments();
    String checkpoint1 = initial.checkpoint();
    assertEquals(SPConstants.CHECKPOINT_VALUE, checkpoint1);
    DocumentList incremental = manager.resumeTraversal(checkpoint1);
    List<SPDocument> pass2 = ((SPDocumentList) incremental).getDocuments();
    assertEquals(pass1.size(), pass2.size());    
  }
}
