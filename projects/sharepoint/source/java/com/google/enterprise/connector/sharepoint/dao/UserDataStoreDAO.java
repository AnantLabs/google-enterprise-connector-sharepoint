// Copyright 2010 Google Inc.
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

package com.google.enterprise.connector.sharepoint.dao;

import com.google.common.annotations.VisibleForTesting;
import com.google.enterprise.connector.sharepoint.cache.UserDataStoreCache;
import com.google.enterprise.connector.sharepoint.client.Util;
import com.google.enterprise.connector.sharepoint.spiimpl.SharepointException;
import com.google.enterprise.connector.spi.Principal;
import com.google.enterprise.connector.spi.SpiConstants.PrincipalType;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.ParameterizedRowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Data Access Object layer for accessing the user data store
 */
public class UserDataStoreDAO extends SharePointDAO {
  private static final Logger LOGGER =
      Logger.getLogger(UserDataStoreDAO.class.getName());

  static final int UDS_MAX_GROUP_NAME_LENGTH = 256;

  private static final String TABLE_NAME = "TABLE_NAME";
  private static final String UDS_COLUMN_GROUP_NAME = "SPGroupName";
  private static final String UDS_COLUMN_USER_NAME = "SPUserName";
  
  private static final int RETRY_ON_ERROR_601_LIMIT = 3;

  private UserDataStoreCache<UserGroupMembership> udsCache;
  private DataSourceTransactionManager transactionManager;
  private ParameterizedRowMapper<UserGroupMembership> rowMapper;

  public UserDataStoreDAO(final DataSource dataSource,
      final QueryProvider queryProvider,
      final ParameterizedRowMapper<UserGroupMembership> rowMapper)
      throws SharepointException {
    super(dataSource, queryProvider);
    if (null == rowMapper) {
      throw new NullPointerException("RowMapper is null. ");
    }
    this.rowMapper = rowMapper;
    confirmEntitiesExistence();
    transactionManager = new DataSourceTransactionManager(dataSource);
    udsCache = new UserDataStoreCache<UserGroupMembership>();
    this.rowMapper = rowMapper;
  }

  public Set<Principal> getSharePointGroupsForSearchUserAndLdapGroups(
      String localNamespace, Collection<Principal> groups, String searchUser)
      throws SharepointException {
    Set<String> groupNames = new HashSet<String>();
    for (Principal group : groups) {
      groupNames.add(group.getName().toLowerCase());
    }
    List<UserGroupMembership> spMemberships =
        getAllMembershipsForSearchUserAndLdapGroups(groupNames, searchUser.toLowerCase());
    Set<Principal> spGroups = new HashSet<Principal>();
    for (UserGroupMembership membership : spMemberships) {
      spGroups.add(Util.getSharePointGroupPrincipal(localNamespace,
          membership.getNamespace(), membership.getGroupName()));
    }
    if (LOGGER.isLoggable(Level.INFO)) {
      StringBuffer sb = new StringBuffer("Resolved ").append(spGroups.size())
          .append(" SP group(s): ");
      for (Principal spGroup : spGroups) {
        sb.append(spGroup.getName()).append(", ");
      }
      LOGGER.info(sb.delete(sb.length() - 2, sb.length()).toString());
    }
    return spGroups;
  }

  /**
   * Retrieves all the {@link UserGroupMembership} to which {@link Set} groups
   * belongs to including the search user.
   *
   * @param groups set of AD groups whose SP groups are to be retrieved;
   *     must not be null
   * @param searchUser the search user name
   * @throws SharepointException
   */
  @VisibleForTesting
  List<UserGroupMembership> getAllMembershipsForSearchUserAndLdapGroups(
      Set<String> groups, String searchUser) throws SharepointException {
    // The list of AD groups may be long and that is very slow on SQL
    // Server. Substitute SQL escaped string literals for the IN
    // values parameter in the SQL query. This is ~100x faster.
    String queryTemplate = getSqlQuery(Query.UDS_SELECT_FOR_ADGROUPS);
    StringBuilder groupBuffer = new StringBuilder();
    groupBuffer.append("'").append(searchUser.replace("'", "''"));
    for (String group : groups) {
      groupBuffer.append("','").append(group.replace("'", "''"));
    }
    groupBuffer.append("'");
    // The null represents the table name substituted by QueryProvider.
    String queryText =
        MessageFormat.format(queryTemplate, null, groupBuffer.toString());

    try {
      return executeGroupMembershipQuery(queryText, rowMapper, 1);
    } catch (Throwable t) {
      throw new SharepointException("Query execution failed while getting "
          + "the membership info of a given user and AD groups.", t);
    }
  }

  private List<UserGroupMembership> executeGroupMembershipQuery(
      String queryText, ParameterizedRowMapper<UserGroupMembership> rowMapper,
      int attempt) throws DataAccessException {
    try {
      return getSimpleJdbcTemplate().query(queryText, rowMapper);
    } catch (DataAccessException e) {
      if (attempt == RETRY_ON_ERROR_601_LIMIT) {
        throw e;
      }
      if (e.getRootCause() instanceof SQLException) {
        SQLException sqlException = (SQLException) (e.getRootCause());
        if (sqlException.getErrorCode() == 601) {
          LOGGER.log(Level.WARNING, "Error executing query [" + queryText + "] "
              + "due to data move. Retrying attempt [" + (attempt + 1)
              + " of " + RETRY_ON_ERROR_601_LIMIT + "].", sqlException);
          return executeGroupMembershipQuery(queryText, rowMapper, attempt + 1);
        } else {
          throw e;
        }
      } else {
        throw e;
      }
    }
  }

  /**
   * Adds a list of {@link UserGroupMembership} into the user data store. From
   * the passed in collection, only those memberships which are not in cache are
   * picked up for the SQL insert. The rest other memberships are removed from
   * the collection.
   * <p/>
   * Note: Hence, this method may (and often does) modify the passed in
   * collection. After the method returns, the caller can ensure that the
   * collection contains only those memberships which the connector really
   * attempted insertion. But, it does not ensure if it was successful or not.
   *
   * @throws SharepointException
   */
  public void addMemberships(Set<UserGroupMembership> memberships)
      throws SharepointException {
    if (null == memberships || memberships.size() == 0) {
      return;
    }
    if (null != udsCache && udsCache.size() > 0) {
      removeAllCached(memberships);
    }
    int[] status = null;
    // There should be at least one entry in memberships before performing batch
    // update.
    if (memberships.size() > 0) {
      Query query = Query.UDS_INSERT;
      SqlParameterSource[] params = createParameter(query, memberships);
      status = batchUpdate(query, params);
    }

    if (null != udsCache && null != status) {
      addAllSucceeded(status, memberships);
    }
  }

  /**
   * Removes all the membership info of a list of users belonging from a
   * specified namespace, from the user data store.
   *
   * @param userIds list of userIds whose memberships are to be removed
   * @param namespace the namespace to which all the users belong
   * @throws SharepointException
   */
  public void removeUserMembershipsFromNamespace(Set<Integer> userIds,
      String namespace) throws SharepointException {
    Set<UserGroupMembership> memberships = new HashSet<UserGroupMembership>();
    for (int userId : userIds) {
      UserGroupMembership membership = new UserGroupMembership();
      membership.setUserId(userId);
      membership.setNamespace(namespace);
      memberships.add(membership);
    }

    if (memberships.size() == 0) {
      return;
    }

    Query query = Query.UDS_DELETE_FOR_USERID_NAMESPACE;
    SqlParameterSource[] params = createParameter(query, memberships);
    batchUpdate(query, params);

    for (UserGroupMembership membership : memberships) {
      udsCache.removeUsingNamespaceView(membership);
    }
  }

  /**
   * Removes all the membership info of a list of groups belonging from a
   * specified namespace, from the user data store.
   *
   * @param groupIds list of groupIds whose memberships are to be removed
   * @param namespace the namespace to which all the groups belong
   * @throws SharepointException
   */
  public void removeGroupMembershipsFromNamespace(Set<Integer> groupIds,
      String namespace) throws SharepointException {
    Set<UserGroupMembership> memberships = new HashSet<UserGroupMembership>();
    for (int groupId : groupIds) {
      UserGroupMembership membership = new UserGroupMembership();
      membership.setGroupId(groupId);
      membership.setNamespace(namespace);
      memberships.add(membership);
    }

    if (memberships.size() == 0) {
      return;
    }

    Query query = Query.UDS_DELETE_FOR_GROUPID_NAMESPACE;
    SqlParameterSource[] params = createParameter(query, memberships);
    batchUpdate(query, params);

    for (UserGroupMembership membership : memberships) {
      udsCache.removeUsingGroupNamespaceView(membership);
    }
  }

  /**
   * Removes all the membership info belonging to a given list of namespaces,
   * from the user data store.
   *
   * @param namespaces list of namespaces whose memberships are to be removed
   * @throws SharepointException
   */
  public void removeAllMembershipsFromNamespace(Set<String> namespaces)
      throws SharepointException {
    Set<UserGroupMembership> memberships = new HashSet<UserGroupMembership>();
    for (String namespace : namespaces) {
      UserGroupMembership membership = new UserGroupMembership();
      membership.setNamespace(namespace);
      memberships.add(membership);
    }

    if (memberships.size() == 0) {
      return;
    }

    Query query = Query.UDS_DELETE_FOR_NAMESPACE;
    SqlParameterSource[] params = createParameter(query, memberships);
    batchUpdate(query, params);

    for (UserGroupMembership membership : memberships) {
      udsCache.removeUsingNamespaceView(membership);
    }
  }

  /**
   * Synchronizes the membership information of all groups identified by the
   * keyset of the passed in map. The groups are picked up as group-namespace
   * view. The synchronization involves deleting all the persisted memberships
   * and adding the most latest ones. </p> This synchronization is performed as
   * one atomic operation using transaction.
   *
   * @param groupMembershipMap identifies groups and their corresponding most
   *          latest membership information
   * @param namespace the namespace to which all the users belong
   * @throws SharepointException
   */
  public void syncGroupMemberships(
      Map<Integer, Set<UserGroupMembership>> groupMembershipMap,
      String namespace) throws SharepointException {
    if (null == groupMembershipMap || groupMembershipMap.size() == 0) {
      return;
    }

    Set<UserGroupMembership> membershipsToDelete = new TreeSet<UserGroupMembership>();
    Set<UserGroupMembership> membershipsToInsert = new TreeSet<UserGroupMembership>();
    for (Integer groupId : groupMembershipMap.keySet()) {
      UserGroupMembership membership = new UserGroupMembership();
      membership.setGroupId(groupId);
      membership.setNamespace(namespace);

      membershipsToDelete.add(membership);
      membershipsToInsert.addAll(groupMembershipMap.get(groupId));
    }

    Query query1 = Query.UDS_DELETE_FOR_GROUPID_NAMESPACE;
    SqlParameterSource[] param1 = createParameter(query1, membershipsToDelete);
    Query query2 = Query.UDS_INSERT;
    SqlParameterSource[] param2 = createParameter(query2, membershipsToInsert);

    int[][] batchStatus = new int[2][];
    DefaultTransactionDefinition def = new DefaultTransactionDefinition();

    TransactionStatus status = transactionManager.getTransaction(def);
    try {
      batchStatus[0] = batchUpdate(query1, param1);
      batchStatus[0] = batchUpdate(query2, param2);
    } catch (Exception e) {
      transactionManager.rollback(status);
      LOGGER.log(Level.WARNING, "Exception occured in transaction processing. Rolling back... ");
    }
    transactionManager.commit(status);

    if (batchStatus != null) {
      // Removal from cache is lenient because it does not harm any
      // functionality. At worst, duplicate insertion will occur
      if (null != batchStatus[0]) {
        for (UserGroupMembership membership : membershipsToDelete) {
          udsCache.removeUsingGroupNamespaceView(membership);
        }
      }

      // Unlike removal, adding into cache should be strict. A wrong
      // insertion will mean that such records will never be able to reach
      // up to the database.
      if (null != batchStatus[1]) {
        addAllSucceeded(batchStatus[1], membershipsToInsert);
      }
    }
  }

  /**
   * To cleanup the cache.
   */
  public void cleanupCache() {
    LOGGER.log(Level.INFO, "Current cache size , before cleanup "
        + udsCache.size());
    udsCache.clearCache();
    LOGGER.log(Level.INFO, "Current cache size , after cleanup "
        + udsCache.size());
  }

  /**
   * Cache can be disabled using this. By default, it's enabled.
   *
   * @param useCache
   */
  public void isUseCache(boolean useCache) {
    if (!useCache) {
      udsCache = null;
    }
  }

  /**
   * Removes all those elements from the passed-in collection that are found in
   * cache.
   */
  private void removeAllCached(Collection<UserGroupMembership> memberships) {
    Iterator<UserGroupMembership> itr = memberships.iterator();
    while (itr.hasNext()) {
      UserGroupMembership membership = itr.next();
      if (udsCache.contains(membership)) {
        itr.remove();
      }
    }
  }

  public void setUdsCache(UserDataStoreCache<UserGroupMembership> udsCache) {
    this.udsCache = udsCache;
  }

  /**
   * A helper method to create parameters values for the execution of queries.
   */
  private static SqlParameterSource[] createParameter(Query query,
      Collection<UserGroupMembership> memberships) throws SharepointException {
    SqlParameterSource[] namedParams = new SqlParameterSource[memberships.size()];
    int count = 0;

    switch (query) {
    case UDS_INSERT:
      for (UserGroupMembership membership : memberships) {
        namedParams[count++] = query.createParameter(membership.getUserId(), membership.getUserName().toLowerCase(), membership.getGroupId(), membership.getGroupName(), membership.getNamespace());
      }
      break;

    case UDS_DELETE_FOR_USERID_NAMESPACE:
      for (UserGroupMembership membership : memberships) {
        namedParams[count++] = query.createParameter(membership.getUserId(), membership.getNamespace());
      }
      break;

    case UDS_DELETE_FOR_GROUPID_NAMESPACE:
      for (UserGroupMembership membership : memberships) {
        namedParams[count++] = query.createParameter(membership.getGroupId(), membership.getNamespace());
      }
      break;

    case UDS_DELETE_FOR_NAMESPACE:
      for (UserGroupMembership membership : memberships) {
        namedParams[count++] = query.createParameter(membership.getNamespace());
      }
      break;

    default:
      throw new SharepointException("Query Not Supported!! ");
    }
    return namedParams;
  }

  /**
   * Checks if all the required entities exist in the user data store DB. If
   * not, creates them. As a minimal check, this method only checks for the
   * existence of tables. Child of this class can extend this for various such
   * checks
   *
   * @throws SharepointException
   */
  private void confirmEntitiesExistence() throws SharepointException {
    DatabaseMetaData dbm = null;
    boolean udsTableFound = false;
    boolean cnTableFound = false;
    String udsTableName, cnTableName = null;
    String udsTablePattern, cnTablePattern = null;
    ResultSet rsTables = null;
    Statement statement = null;

    try {
      dbm = getConnection().getMetaData();
      udsTableName = getQueryProvider().getUdsTableName();
      cnTableName = getQueryProvider().getCnTableName();

      if (dbm.storesUpperCaseIdentifiers()) {
        udsTablePattern = udsTableName.toUpperCase();
        cnTablePattern = cnTableName.toUpperCase();
      } else if (dbm.storesLowerCaseIdentifiers()) {
        udsTablePattern = udsTableName.toLowerCase();
        cnTablePattern = cnTableName.toLowerCase();
      } else {
        udsTablePattern = udsTableName;
        cnTablePattern = cnTableName;
      }
      // specific to user data store table pattern.
      udsTablePattern = udsTablePattern.replace("%", dbm.getSearchStringEscape()
          + "%");
      udsTablePattern = udsTablePattern.replace("_", dbm.getSearchStringEscape()
          + "_");
      // specific to connector names table pattern.
      cnTablePattern = cnTablePattern.replace("%", dbm.getSearchStringEscape()
          + "%");
      cnTablePattern = cnTablePattern.replace("_", dbm.getSearchStringEscape()
          + "_");

      // Specific to oracle database to check required entities in user
      // data store data base.
      if (getQueryProvider().getDatabase().equalsIgnoreCase("oracle")) {
        statement = getConnection().createStatement(
            ResultSet.TYPE_SCROLL_INSENSITIVE,
            ResultSet.CONCUR_READ_ONLY);
        String query = getSqlQuery(Query.UDS_CHECK_TABLES);
        rsTables = statement.executeQuery(query);
        udsTableFound = isTableNameExists(udsTableName, rsTables, true);
        cnTableFound = isTableNameExists(cnTableName, rsTables, true);
      } else {
        rsTables = dbm.getTables(null, null, udsTablePattern, null);
        udsTableFound = isTableNameExists(udsTableName, rsTables, false);
        rsTables = dbm.getTables(null, null, cnTablePattern, null);
        cnTableFound = isTableNameExists(cnTableName, rsTables, false);
      }

      try {
        rsTables.close();
        if (null != statement) {
          statement.close();
        }
      } catch (SQLException e) {
        LOGGER.log(Level.WARNING, "Exception occurred while closing data base resources.", e);
      }
      if (!udsTableFound) {
        getSimpleJdbcTemplate().update(getSqlQuery(Query.UDS_CREATE_TABLE));
        LOGGER.config("Created user data store table with name : "
            + Query.UDS_CREATE_TABLE + " sucessfully");
        getSimpleJdbcTemplate().update(getSqlQuery(Query.UDS_CREATE_INDEX));
        LOGGER.config("Created user data store table index with name : "
            + Query.UDS_CREATE_INDEX + " sucessfully");
      } else {
        updateUDSTable(dbm, udsTablePattern);
      }
      if (!cnTableFound) {
        getSimpleJdbcTemplate().update(getSqlQuery(Query.CN_CREATE_TABLE));
        LOGGER.config("Created connector names table with name : "
            + Query.UDS_CREATE_TABLE + " sucessfully");
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Exception occurred while getting the table information from the database metadata. ", e);
    }
  }

  /**
   * Updates updates the user data table. 
   *
   * @param dbm the DatabaseMetaData for the DB
   * @param tablePattern the DB table pattern to get information for
   */
  private void updateUDSTable(DatabaseMetaData dbm, String tablePattern)
      throws SQLException {
    updateColumnIfNeeded(dbm, tablePattern,
        UDS_COLUMN_USER_NAME,
        UDS_MAX_GROUP_NAME_LENGTH,
        Query.UDS_UPGRADE_COL_USERNAME);
    updateColumnIfNeeded(dbm, tablePattern,
        UDS_COLUMN_GROUP_NAME,
        UDS_MAX_GROUP_NAME_LENGTH,
        Query.UDS_UPGRADE_COL_GROUPNAME);
  }

  /**
   * Updates a column if the column length is less that the specified length. 
   *
   * @param dbm the DatabaseMetaData for the DB
   * @param tablePattern the DB table pattern to get information for
   * @param tableColumn the DB column name
   * @param minColumnLength the minimum length the column length should be
   * @param query the query to use to update the table column
   */
  private void updateColumnIfNeeded(DatabaseMetaData dbm,
      String tablePattern, String tableColumn, int minColumnLength,
      Query query) throws SQLException {
    int columnLength = getColumnLength(dbm, tablePattern, tableColumn);
    if (minColumnLength > columnLength) {
      getSimpleJdbcTemplate().update(getSqlQuery(query));
      LOGGER.info("Upgraded user data store table column " + tableColumn
          + " with name " + query + ".");
    }
  }

  /**
   * Gets the length of a table column. 
   *
   * @param dbm the DatabaseMetaData for the DB
   * @param tablePattern the DB table pattern to get information for
   * @param tableColumn the DB column name
   * @return the length of the column or 0 on failure
   */
  private int getColumnLength(DatabaseMetaData dbm, String tablePattern,
      String tableColumn) throws SQLException {
    ResultSet rsColumns = dbm.getColumns(null, null, tablePattern,
        adjustDBIdentifier(dbm, tableColumn));
    int columnLength = 0;
    if (rsColumns.next()) {
      LOGGER.info("COLUMN_SIZE: " + rsColumns.getInt("COLUMN_SIZE")
          + ";  COLUMN_NAME: " + rsColumns.getString("COLUMN_NAME") 
          + ";  NULLABLE: " + rsColumns.getInt("NULLABLE"));
      columnLength = rsColumns.getInt("COLUMN_SIZE");
      rsColumns.close();
    }
    return columnLength;
  }

  /**
   * Adjusts the name of a table indetifier based on the DatabaseMetaData 
   * capitalization so that indetifier can be used to query the DB.
   *
   * @param dbm the DatabaseMetaData for the DB
   * @param name the indetifier
   * @return the indetifier with the correct capitalization
   */
  private String adjustDBIdentifier(DatabaseMetaData dbm, String name)
      throws SQLException {
    if (dbm.storesUpperCaseIdentifiers()) {
      return name.toUpperCase();
    } else if (dbm.storesLowerCaseIdentifiers()) {
      return name.toLowerCase();
    } else {
      return name;
    }
  }

  /**
   * Adds element into the cache after performing strict checking against cache.
   * <p>
   * In case of Oracle data base, -2 for new memberships and -1 for existing
   * records in user data store.
   * </p>
   * <p>
   * In case of MYSQL, +ve integer for new memberships and -3 for existing
   * records in user data store.
   * </p>
   * <p>
   * In case of MS SQL, +ve integer for new memberships and -3 for existing
   * records in user data store.
   * </p>
   */
  private void addAllSucceeded(int[] status,
      Collection<UserGroupMembership> memberships) {
    int i = 0;
    if (null != udsCache) {
      for (UserGroupMembership membership : memberships) {
        // Cache all the memberships whose entries are missed in cache
        // as well as the new entries that are inserted successfully
        // into user data store.
        if (!udsCache.contains(membership)) {
          // -1 represents missed cache entry and -2 represent newly
          // inserted membership in Oracle data base. in MS SQL
          // data base and -3 represents missed cache entry in MY SQL
          // data base. hence it makes sense to have a check against
          // status >= -3.
          if (status[i] >= -3) {
            udsCache.add(membership);
          }
        }
        i++;
      }
    }
  }

  public int getUdsCacheSize() {
    return this.udsCache.size();
  }

  /**
   * Removes the user data store table.
   *
   * @throws SharepointException
   */
  public void dropUserDataStoreTable() throws SharepointException {
    int status = getSimpleJdbcTemplate().update(getSqlQuery(Query.UDS_DROP_TABLE));
    if (status == 0) {
      LOGGER.info("Sucessfully dropped the User_Groups_Memberships table from the database using the query [ "
          + Query.CN_DROP_TABLE + " ]");
    }
  }

  /**
   * Helper method to find out the given table name in the result set.
   *
   * @param tableName name to find out in the result set.
   * @param rsTables is the result set
   * @param fromStatement a flag indicating whether the result set comes
            from a prepared statement.
   * @return true if the given table name found in the result set.
   * @throws SQLException
   */
  private boolean isTableNameExists(String tableName, ResultSet rsTables,
      boolean fromStatement) throws SQLException {
    boolean tableFound = false;

    // Only reset the result set if it comes from a prepared statement.
    // If the result set comes from a getTables call then if may not 
    // support the required opperations.
    if (fromStatement) {
      if (!rsTables.isBeforeFirst()) {
        rsTables.beforeFirst();
      }
    }

    while (rsTables.next()) {
      String currName;
      if (fromStatement) {
        currName = rsTables.getString(1);
      } else {
        currName = rsTables.getString(TABLE_NAME);
      }
      if (tableName.equalsIgnoreCase(currName)) {
        tableFound = true;
        LOGGER.log(Level.FINE, "Table name [ " + tableName
            + " ]  found in database.");
        break;
      }
    }
    return tableFound;
  }
}
