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

package com.google.enterprise.connector.sharepoint.wsclient.soap;

import com.google.enterprise.connector.sharepoint.TestConfiguration;
import com.google.enterprise.connector.sharepoint.client.AlertsHelper;
import com.google.enterprise.connector.sharepoint.client.SPConstants;
import com.google.enterprise.connector.sharepoint.client.SharepointClientContext;
import com.google.enterprise.connector.sharepoint.spiimpl.SPDocument;
import com.google.enterprise.connector.sharepoint.state.GlobalState;
import com.google.enterprise.connector.sharepoint.state.ListState;
import com.google.enterprise.connector.sharepoint.state.WebState;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import junit.framework.TestCase;

public class SPAlertsWSTest extends TestCase {
  SharepointClientContext sharepointClientContext;
  AlertsHelper alerts;
  SPClientFactory clientFactory = new SPClientFactory();

  protected void setUp() throws Exception {
    System.out.println("\n...Setting Up...");
    System.out.println("Initializing SharepointClientContext ...");
    this.sharepointClientContext = TestConfiguration.initContext();

    assertNotNull(this.sharepointClientContext);
    sharepointClientContext.setIncluded_metadata(TestConfiguration.whiteList);
    sharepointClientContext.setExcluded_metadata(TestConfiguration.blackList);

    alerts = new AlertsHelper(this.sharepointClientContext);
  }

  public final void testAlertsWS() throws Throwable {
    sharepointClientContext.setSiteURL(TestConfiguration.Site1_URL);
    alerts = new AlertsHelper(this.sharepointClientContext);
    assertNotNull(alerts);
  }

  public final void testAlerts() throws Throwable {
    final String internalName = this.sharepointClientContext.getSiteURL();
    final Calendar cLastMod = Calendar.getInstance();
    cLastMod.setTime(new Date());
    GlobalState gs = new GlobalState(clientFactory,
        TestConfiguration.googleConnectorWorkDir,
        TestConfiguration.feedType);
    WebState ws = gs.makeWebState(sharepointClientContext, TestConfiguration.Site1_URL);
    final ListState currentDummyAlertList = new ListState(internalName,
        SPConstants.ALERTS_TYPE, SPConstants.ALERTS_TYPE, cLastMod,
        SPConstants.ALERTS_TYPE, internalName, ws);

    List<SPDocument> lstAlerts = alerts.getAlerts(ws, currentDummyAlertList);
    assertNotNull(lstAlerts);
  }
}
