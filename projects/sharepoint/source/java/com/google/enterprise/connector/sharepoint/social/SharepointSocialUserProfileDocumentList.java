// Copyright 2012 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.sharepoint.social;

import com.google.common.base.Strings;
import com.google.enterprise.connector.sharepoint.client.SharepointClientContext;
import com.google.enterprise.connector.spi.Document;
import com.google.enterprise.connector.spi.DocumentList;
import com.google.enterprise.connector.spi.Property;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SocialUserProfileDocument;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;
import com.google.enterprise.connector.util.SystemClock;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Document list is implemented for user profile documents for SharePoint. The
 * checkpoint implementation on this document list simply keeps track of the
 * index for the next document. On resume we
 * fetch from here. However, it is possible that profiles have been
 * added/deleted at source in the mean time. Further, SharePoint does not give
 * any explicit guarantee that the next time things will be in the same order.
 * Hence there is a theoretical possibility that by checkpoint/resume we may
 * miss a few profiles once a while. In order to reduce that possibility, on
 * resume, when we validate the checkpoint, we check if profile count has
 * remained same or not. If the count differs then we assume that is the new
 * good value and pretend we had been fetching from the new list. Another
 * approach would be to actually fetch all of them in one shot and save then in
 * a local store and then serve from there. That would be expensive and even
 * that is not fool-proof strategy since for large number of user-profiles
 * connection can break in the middle. For the current strategy a work-around
 * would be to restart the feed and increase checkpoint interval. We will
 * consider the other strategy later, based on observation on how things go.
 * Also assuming, missing a few experts once a while, and rarely, is not
 * critical.
 *
 * @author tapasnay
 */

public class SharepointSocialUserProfileDocumentList implements DocumentList {

  final static Logger LOGGER = SharepointSocialConnector.LOGGER;
  public static final String CHECKPOINT_PREFIX = "sp_userprofile";
  private final SharepointUserProfileConnection service;
  private long lastFullSync = 0L;
  private int nextProfileIndex = -1;
  private boolean end = false;
  private final int batchHint;
  private int batchCount = 0;
  private String userProfileChangeToken = "";
  private List<SharePointSocialUserProfileDocument> updatedDocuments;


  public SharepointSocialUserProfileDocumentList(
      SharepointUserProfileConnection connection,
      SharePointSocialCheckpoint checkpoint, int batchHint,
      List<SharePointSocialUserProfileDocument> updatedDocuments)
          throws RepositoryException {
    service = connection;
    try {
      int profileCount = connection.openConnection();
      LOGGER.info("Total Profile Count = " + profileCount);
    } catch (RemoteException e) {
      throw new RepositoryException(e);
    }
    if (checkpoint.isEmptyUserProfileCheckpoint()) {
      this.nextProfileIndex = -1;
    } else {
      // This is incremental crawl. Connector will use next Profile Index
      // value to fetch next profile.
      this.nextProfileIndex = checkpoint.getUserProfileNextIndex();
    }
    lastFullSync = checkpoint.getUserProfileLastFullSync();
    this.updatedDocuments = updatedDocuments;
    userProfileChangeToken = checkpoint.getUserProfileChangeToken();
    this.batchHint = batchHint;
  }



  @Override
  public String checkpoint() throws RepositoryException {
   SharePointSocialCheckpoint checkpoint = new SharePointSocialCheckpoint("");
   checkpoint.setUserProfileLastFullSync(lastFullSync);
   checkpoint.setUserProfileNextIndex(nextProfileIndex);
   checkpoint.setUserProfileChangeToken(userProfileChangeToken);
   return CHECKPOINT_PREFIX + checkpoint.toString();
  }

  @Override
  public Document nextDocument() throws RepositoryException {
    while (updatedDocuments != null && updatedDocuments.size() > 0) {
      SharePointSocialUserProfileDocument updatedDoc =
          updatedDocuments.remove(0);
      if (updatedDoc.getActionType() == ActionType.DELETE) {
        LOGGER.fine("Returning deleted Doc = "
            + updatedDoc);
        batchCount++;
        return updatedDoc;
      }
      String userAccount = updatedDoc.getUserKey().toString();
      if (!Strings.isNullOrEmpty(userAccount)) {
        try {
          SocialUserProfileDocument updatedProfile =
              service.getProfileByName(userAccount);
          if (updatedProfile != null) {
            batchCount++;
            return updatedProfile;
          }
        } catch (Exception e) {
          // Ignoring exceptions so connector can process next change.
          LOGGER.log(Level.WARNING, "Error fetching updated profile for = "
                  + userAccount, e);
        }
      }
    }

    if (end) {
      return null;
    }
    if (batchCount >= batchHint) {
      LOGGER.fine("Returning null as reached batch hint = "
          + batchHint);
      return null;
    }
    LOGGER.fine("Returning userprofile at index = " + nextProfileIndex);
    Document doc = null;
    try {
      doc = fetchNextProfile(true);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING,
          "There was a failure fetching profile at index = "
              + nextProfileIndex, e);
    }
    batchCount++;
    return doc;
  }

  private void markEnd() {
    LOGGER.fine("Marking end");
    if (lastFullSync == 0L) {
      // lastFullSync = 0L indicates this was part of full crawl.
      lastFullSync = new SystemClock().getTimeMillis();
    }
    end = true;
  }

  private Document fetchNextProfile(boolean retry) throws RepositoryException {
    Document doc = null;
    try {
      doc = service.getProfile(nextProfileIndex);
      if (doc != null) {
        SharePointSocialUserProfileDocument socialDoc =
            (SharePointSocialUserProfileDocument) doc;
        if (socialDoc.getNextValue() != -1 &&
            socialDoc.getNextValue() != nextProfileIndex) {
          nextProfileIndex = socialDoc.getNextValue();
          LOGGER.fine("Setting Next Index = " + nextProfileIndex);
        } else {
          LOGGER.fine("Marking end with socialDoc.getNextValue() = "
              + socialDoc.getNextValue());
          markEnd();
        }
      } else {
        LOGGER.fine("Marking end as Doc is null");
        markEnd();
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING,
          "There was a failure fetching profile at index = "
              + nextProfileIndex + " retrying...", e);
      if (retry) {
        try {
          // if openConnection throws service is out of bounds,
          // we release current batch and wait for resume
          service.openConnection();
        } catch (RemoteException eAgain) {
          LOGGER.log(Level.WARNING, "User profile service not reachable,"
              + " continuing with partial list, to be resumed", eAgain);
          return null;
        }
        doc = fetchNextProfile(false);
      }
    }
    return doc;
  }
}