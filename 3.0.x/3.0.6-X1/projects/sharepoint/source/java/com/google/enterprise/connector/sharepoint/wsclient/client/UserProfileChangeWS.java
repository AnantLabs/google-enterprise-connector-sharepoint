package com.google.enterprise.connector.sharepoint.wsclient.client;

import com.google.enterprise.connector.sharepoint.social.SharePointSocialCheckpoint;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;

import java.util.Map;

public interface UserProfileChangeWS {
  /**
   * Gets the list of User profiles  updated \ deleted as per Change Token available in
   * input checkpoint
   * @param checkpoint Checkpoint with change token
   * @return List of User account names for which user profiles are updated \ deleted
   */
  public Map<String, ActionType> getChangedUserProfiles(
      SharePointSocialCheckpoint checkpoint);
  
  /**
   * Gets latest change token from SharePoint for User Profile Changes 
   * @return latest change token for user profiles
   * @throws Exception
   */
  public String getCurrentChangeToken() throws Exception;

}
