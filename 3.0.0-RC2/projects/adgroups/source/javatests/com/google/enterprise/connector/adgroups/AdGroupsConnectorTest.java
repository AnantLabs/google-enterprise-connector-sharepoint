// Copyright 2012 Google Inc.
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

package com.google.enterprise.connector.adgroups;

import com.google.enterprise.connector.adgroups.AdConstants.Method;
import com.google.enterprise.connector.adgroups.TestConfiguration;
import com.google.enterprise.connector.spi.AuthenticationManager;
import com.google.enterprise.connector.spi.AuthenticationResponse;
import com.google.enterprise.connector.spi.Principal;
import com.google.enterprise.connector.spi.Session;
import com.google.enterprise.connector.spi.SimpleAuthenticationIdentity;

import junit.framework.TestCase;

import java.util.Collection;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

public class AdGroupsConnectorTest extends TestCase {
  private static final Logger LOGGER =
      Logger.getLogger(AdDbUtil.class.getName());

  private void runUsernameTest(String comment, AuthenticationManager am,
      String username, String domain, String password) 
      throws Exception {
    AuthenticationResponse response;
    
    response = am.authenticate(
        new SimpleAuthenticationIdentity(username));
    assertTrue(comment + ": Username, no domain, no password",
        response.isValid());
    
    response = am.authenticate(new SimpleAuthenticationIdentity(
        username, null));
    assertTrue(comment + ": Username, no domain, null password",
        response.isValid());
    
    response = am.authenticate(new SimpleAuthenticationIdentity(
        username, ""));
    assertFalse(comment + ": Username, no domain, empty password",
        response.isValid());
    
    response = am.authenticate(
        new SimpleAuthenticationIdentity(username, null, domain));
    assertTrue(comment + ": Username, domain, null password",
        response.isValid());
    
    response = am.authenticate(new SimpleAuthenticationIdentity(
            username, "", domain));
    assertFalse(comment + ": Username, domain, empty password",
        response.isValid());
    
    response = am.authenticate(new SimpleAuthenticationIdentity(
        username, password, domain));
    assertTrue(comment + ": Username, domain, password",
        response.isValid());
    
    response = am.authenticate(new SimpleAuthenticationIdentity(
        username, password + "makeinvalid"));
    assertFalse(comment + ": Username, no domain, incorrect password",
        response.isValid());
    
    response = am.authenticate(new SimpleAuthenticationIdentity(
        username, password + "makeinvalid", domain));
    assertFalse(comment + ": Username, domain, incorrect password",
        response.isValid());
  }

  public void testSimpleCrawl() throws Exception {
    for (String dbType : TestConfiguration.dbs.keySet()) {
      AdGroupsConnector con = new AdGroupsConnector();
      LOGGER.info("Testing database: " + dbType);
      
      con.setMethod("SSL");
      con.setHostname(TestConfiguration.d1hostname);
      con.setPort(Integer.toString(TestConfiguration.d1port));
      con.setPrincipal(TestConfiguration.d1principal);
      con.setPassword(TestConfiguration.d1password);
      
      con.setDataSource(dbType, TestConfiguration.dbs.get(dbType));
      Session s = con.login();
      s.getTraversalManager().startTraversal();
      AuthenticationManager am = s.getAuthenticationManager();
      
      AuthenticationResponse response = am.authenticate(
          new SimpleAuthenticationIdentity(
              "non-existing user", "wrong password", "wrong domain"));
      assertFalse("Non existing user fails authn", response.isValid());
      assertNull("No groups resolved for non-existing user", 
          response.getGroups());
      
      String[] principal =
          TestConfiguration.d1principal.split("\\\\"); 
      String domain = principal[0];
      String username = principal[1];
      
      runUsernameTest("Normal case",
          am, username, domain, TestConfiguration.d1password);
      
      runUsernameTest("Uppercase username",
          am, username.toUpperCase(), domain, TestConfiguration.d1password);
      
      runUsernameTest("Lowercase username",
          am, username.toLowerCase(), domain, TestConfiguration.d1password);
      
      runUsernameTest("Uppercase domain",
          am, username.toUpperCase(), domain, TestConfiguration.d1password);
      
      runUsernameTest("Lowercase domain",
          am, username.toLowerCase(), domain, TestConfiguration.d1password);
    }
  }

  public void testScalability() throws Exception {
    AdTestServer ad = new AdTestServer(
        Method.SSL, 
        TestConfiguration.d1hostname, 
        TestConfiguration.d1port,
        TestConfiguration.d1principal,
        TestConfiguration.d1password);
    ad.initialize();
    if (!TestConfiguration.prepared) {
      ad.deleteOu(TestConfiguration.testOu);
      ad.createOu(TestConfiguration.testOu);
    }
    ad.generateUsersAndGroups(
        TestConfiguration.prepared,
        TestConfiguration.testOu,
        new Random(TestConfiguration.seed),
        TestConfiguration.groupsPerDomain,
        TestConfiguration.usersPerDomain);

    for (String dbType : TestConfiguration.dbs.keySet()) {
      AdGroupsConnector con = new AdGroupsConnector();
      LOGGER.info("Testing database: " + dbType);
      
      con.setMethod("SSL");
      con.setHostname(TestConfiguration.d1hostname);
      con.setPort(Integer.toString(TestConfiguration.d1port));
      con.setPrincipal(TestConfiguration.d1principal);
      con.setPassword(TestConfiguration.d1password);

      con.setDataSource(dbType, TestConfiguration.dbs.get(dbType));
      Session s = con.login();
      s.getTraversalManager().startTraversal();
      AuthenticationManager am = s.getAuthenticationManager();

      for (AdTestEntity user : ad.users) {
        AuthenticationResponse response = am.authenticate(
            new SimpleAuthenticationIdentity(user.sAMAccountName));

        Set<AdTestEntity> groupsCorrect = user.getAllGroups();

        Set<String> groups = new HashSet<String>();
        for (Principal p : (Collection<Principal>) response.getGroups()) {
          groups.add(p.getName());
        }

        for (AdTestEntity e : groupsCorrect) {
          assertTrue(groups.contains(ad.getnETBIOSName()
              + AdConstants.BACKSLASH + e.sAMAccountName));
        }
      }
    }
  }
}