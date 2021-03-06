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

package com.google.enterprise.connector.sharepoint.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.enterprise.connector.sharepoint.client.SharepointClientContext;
import com.google.enterprise.connector.sharepoint.client.SPConstants.FeedType;
import com.google.enterprise.connector.sharepoint.client.Util;
import com.google.enterprise.connector.sharepoint.spiimpl.SharepointException;
import com.google.enterprise.connector.sharepoint.spiimpl.SPDocument;
import com.google.enterprise.connector.sharepoint.state.ListState;
import com.google.enterprise.connector.sharepoint.wsclient.util.DateUtil;
import com.google.enterprise.connector.spi.SpiConstants.DocumentType;

import org.apache.axis.message.MessageElement;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFactory;

/**
 * Utility class with helpers for querying and parsing Sharepoint ListsWS 
 * services.
 */
public abstract class ListsUtil {
  private static final Logger LOGGER = Logger.getLogger(ListsUtil.class.getName());

  /**
   * Used to create WS query in case of SP2003
   *
   * @param c The minimal date, items returned are later that this date
   * @param listItemID The minimal ID, items returned have an ID greater that this ID
   * @return the created query being used for WS call
   * @throws ParserConfigurationException
   * @throws IOException
   * @throws SAXException
   * @throws ParseException
   */
  public static MessageElement[] createQuery(final Calendar c, String listItemID)
      throws ParserConfigurationException, IOException, SAXException, ParseException {
    // To ensure a properList ItemID...case of List where GUID is found
    boolean isList;
    try {
      isList = false;
      Integer.parseInt(listItemID);
    } catch (final Exception e) {
      listItemID = "0";
      // change the query
      isList = true;
    }

    String date;
    if (c != null) {
      date = DateUtil.calendarToIso8601(c, DateUtil.Iso8601DateAccuracy.SECS);
      LOGGER.config("The ISO 8601 date passed to WS for change detection of "
          + "content modified since is: " + date);
    } else {
      date = null;
    }

    String strMyString;
    if (((date == null) || (listItemID == null))) {
      LOGGER.config("Initial case ...");
      strMyString = "<Query>"
          + "<OrderBy><FieldRef Name=\"Modified\" Ascending=\"TRUE\" /></OrderBy>"
          + "</Query>";
    } else if (isList) {
      LOGGER.config("list case...");
      strMyString = ""
          + "<Query>"
          + "<Where>"
          + "<Gt>"
          + "<FieldRef Name=\"Modified\"/>"
          + "<Value Type=\"DateTime\" IncludeTimeValue=\"TRUE\" StorageTZ=\"TRUE\">"
          + date
          + "</Value>"
          + "</Gt>"
          + "</Where>"
          + "<OrderBy><FieldRef Name=\"Modified\" Ascending=\"TRUE\" /></OrderBy>"
          + "</Query>";
    } else {
      LOGGER.config("other cases ...");
      strMyString = ""
          + "<Query>"
          + "<Where>"
          + "<Or>"
          + "<Gt>"
          + "<FieldRef Name=\"Modified\"/>"
          + "<Value Type=\"DateTime\" IncludeTimeValue=\"TRUE\" StorageTZ=\"TRUE\">"
          + date
          + "</Value>"
          + "</Gt>"
          + "<And>"
          + "<Eq>"
          + "<FieldRef Name=\"Modified\"/>"
          + "<Value Type=\"DateTime\" IncludeTimeValue=\"TRUE\" StorageTZ=\"TRUE\">"
          + date
          + "</Value>"
          + "</Eq>"
          + "<Gt>"
          + "<FieldRef Name=\"ID\"/>"
          + "<Value Type=\"Text\">"
          + listItemID
          + "</Value>"
          + "</Gt>"
          + "</And>"
          + "</Or>"
          + "</Where>"
          + "<OrderBy><FieldRef Name=\"Modified\" Ascending=\"TRUE\" /></OrderBy>"
          + "</Query>";
    }
    return new MessageElement[] { getMeFromString(strMyString) };
  }
  
  /**
   * For getting only folders starting from a given ID.
   *
   * @param listItemID The minimal ID, items returned have an ID greater that this ID
   * @return the created query being used for WS call
   * @throws ParserConfigurationException
   * @throws IOException
   * @throws ParseException
   */
  public static MessageElement[] createQuery1(String listItemID)
      throws ParserConfigurationException, IOException, SAXException {
    try {
      Integer.parseInt(listItemID);
    } catch (final Exception e) {
      listItemID = "0";
    }
    final String strMyString = "<Query><Where><And><Gt>"
        + "<FieldRef Name=\"ID\"/><Value Type=\"Counter\">" + listItemID
        + "</Value></Gt><Eq><FieldRef Name=\"FSObjType\"/>"
        + "<Value Type=\"Lookup\">1</Value></Eq></And></Where>"
        + "<OrderBy><FieldRef Name=\"ID\" Ascending=\"TRUE\" /></OrderBy>"
        + "</Query>";

    final MessageElement[] meArray = { getMeFromString(strMyString) };
    return meArray;
  }

  /**
   * For getting documents and folders; starting from a given
   * lastItemID
   *
   * @param listItemID The minimal ID, items returned have an ID greater that this ID
   * @return the created query being used for WS call
   * @throws ParserConfigurationException
   * @throws IOException
   * @throws ParseException
   */
  public static MessageElement[] createQuery2(String listItemID)
      throws ParserConfigurationException, IOException, SAXException {
    try {
      Integer.parseInt(listItemID);
    } catch (final Exception e) {
      // Eatup the exception. This was just to check whether it is a list
      // or listItem.
      listItemID = "0";
    }
    final String strMyString = "" + "<Query>" + "<Where>" + "<Gt>"
            + "<FieldRef Name=\"ID\"/>" + "<Value Type=\"Counter\">"
            + listItemID + "</Value>" + "</Gt>" 
            + "</Where>"
            + "<OrderBy><FieldRef Name=\"ID\" Ascending=\"TRUE\" /></OrderBy>"
            + "</Query>";

    final MessageElement[] meArray = { getMeFromString(strMyString) };
    return meArray;
  }
  
  
 
  public static MessageElement[] createQuerySubFolders(String parentFolderPath)
      throws ParserConfigurationException, IOException, SAXException {
   
    final String query = "<Query><Where><And><BeginsWith>"
        + "<FieldRef Name=\"FileRef\"/><Value Type=\"Lookup\">" + parentFolderPath
        + "</Value></BeginsWith><Eq><FieldRef Name=\"FSObjType\"/>"
        + "<Value Type=\"Lookup\">1</Value></Eq></And></Where>"
        + "<OrderBy><FieldRef Name=\"ID\" Ascending=\"TRUE\" /></OrderBy></Query>";

    final MessageElement[] meArray = { getMeFromString(query) };
    return meArray;
  }
  
  public static MessageElement[] createQueryInsideFolder(String parentFolderPath)
      throws ParserConfigurationException, IOException, SAXException {
   
    final String query = "<Query><Where><BeginsWith>"
        + "<FieldRef Name=\"FileDirRef\"/><Value Type=\"Lookup\">" + parentFolderPath
        + "</Value></BeginsWith></Where>"
        + "<OrderBy><FieldRef Name=\"ID\" Ascending=\"TRUE\" /></OrderBy></Query>";

    final MessageElement[] meArray = { getMeFromString(query) };
    return meArray;
  }
  
  

  /**
   * For getting the documents starting from a given lastItemID, but we also
   * want to get folders which are independent of the lastItemID constraint.
   *
   * @param listItemID The minimal ID, items returned have an ID greater that this ID
   * @return the created query being used for WS call
   * @throws ParserConfigurationException
   * @throws IOException
   * @throws ParseException
   */
  public static MessageElement[] createQuery3(String listItemID)
      throws ParserConfigurationException, IOException, SAXException {
    // If listItemID is not a number then it must be a list and use the listItemID
    // of zero to signal all items of the list.
    if (!Util.isNumeric(listItemID)) {
      listItemID = "0";
    }

    final String strMyString = "<Query><Where><Or><Gt>"
        + "<FieldRef Name=\"ID\"/><Value Type=\"Counter\">" + listItemID
        + "</Value></Gt><Eq><FieldRef Name=\"FSObjType\"/>"
        + "<Value Type=\"Lookup\">1</Value></Eq></Or>"
        + "</Where>"
        + "<OrderBy><FieldRef Name=\"ID\" Ascending=\"TRUE\" /></OrderBy>"
        + "</Query>";

    return new MessageElement[] { getMeFromString(strMyString) };
  }

  /**
   * Returns a MessageElement element object for a given string in xml format
   *
   * @param strMyString The string used as the contents of the message element
   * @return the created query being used for WS call
   * @throws ParserConfigurationException
   * @throws IOException
   * @throws SAXException
   */
  public static MessageElement getMeFromString(final String strMyString)
      throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    final StringReader reader = new StringReader(strMyString);
    final InputSource inputsource = new InputSource(reader);
    Document doc = docBuilder.parse(inputsource);
    final Element ele = doc.getDocumentElement();
    return new MessageElement(ele);
  }

  /**
   * View Fields required for making web service call
   *
   * @return the view fields being used for WS call
   */
  public static MessageElement[] createViewFields() {
    final String sViewFields = "ViewFields";
    final MessageElement me = new MessageElement(new QName(sViewFields));
    me.addAttribute(null, "Properties", new QName("TRUE"));
    final MessageElement[] meArray = { me };
    return meArray;
  }

  /**
   * Generates the query options required for making Sharepoint lists
   * web service requests.
   *
   * @param recursion flag to indicate if folders should be returned as 
   *        part of query results
   * @param nextPage
   * @return the query options being used for WS call
   */
  public static MessageElement[] createQueryOptions(final boolean recursion,
      final String nextPage) {
    final MessageElement me = new MessageElement(new QName("QueryOptions"));
    try {
      me.addChildElement(new MessageElement(
          new QName("IncludeMandatoryColumns"))).addTextNode("true");
      me.addChildElement(new MessageElement(new QName("DateInUtc"))).addTextNode("TRUE");

      if (recursion) {
        me.addChildElement(new MessageElement(new QName("ViewAttributes"))).addAttribute(
            SOAPFactory.newInstance().createName("Scope"), SPConstants.RECURSIVE);

        // added for getting folder information when recursion is being
        // used, in case of getListItemChangesSinceToken.
        me.addChildElement(new MessageElement(new QName("OptimizeFor"))).addTextNode("ItemIds");
      }

      if (!Strings.isNullOrEmpty(nextPage)) {
        me.addChildElement(new MessageElement(new QName("Paging"))).addAttribute(
            SOAPFactory.newInstance().createName("ListItemCollectionPositionNext"), nextPage);
      }
    } catch (final SOAPException se) {
      LOGGER.log(Level.WARNING, "Problem while creating Query Options.", se);
    }
    final MessageElement[] meArray = { me };
    return meArray;
  }

  /**
   * Process the rs:changes element as returned by getListItemChangesSinceToken.
   *
   * @param changeElement The root child node that contains all the changes
   * @param list  Base List
   * @param deletedIDs Set of deleted IDs. Delete feed will be constructed for
   *          them.
   * @param restoredIDs Set of restored IDs. New feeds are sent for these
   *          items.
   * @param renamedIDs If it is a folder. New feeds are sent for all the items
   *          beneath it.
   * @throws SharepointException
   */
  public static void processListChangesElement(
      final SharepointClientContext sharepointClientContext, 
      final MessageElement changeElement, final ListState list, 
      final Set<String> deletedIDs, final Set<String> restoredIDs, 
      final Set<String> renamedIDs) throws SharepointException {
    final String lastChangeToken = changeElement.getAttributeValue(SPConstants.LASTCHANGETOKEN);
    LOGGER.log(Level.FINE, "Change Token Received [ " + lastChangeToken
        + " ]. ");
    boolean saveChangeToken = true;
    if (lastChangeToken == null) {
      LOGGER.log(Level.WARNING, "No Change Token Found in the Web Service Response !!!! "
          + "The current change token might have become invalid; please check the "
          + "Event Cache table of SharePoint content database.");
    }

    Iterator<?> itrchild = changeElement.getChildElements();
    while (itrchild.hasNext()) {
      final MessageElement change = (MessageElement) itrchild.next();
      if (null == change) {
        continue;
      }

      if (SPConstants.LIST.equalsIgnoreCase(change.getLocalName())) {
        if (list.isExisting() 
            && !Strings.isNullOrEmpty(list.getChangeTokenForWSCall())) {
          LOGGER.log(Level.INFO, "Resetting Known List [" + list.getListURL() 
              + "] as List metadata modified");
          list.resetState();
          saveChangeToken = false;
        }
        list.setNewList(true);
        list.setExisting(false);        
        continue;
      }

      final String changeType = change.getAttributeValue(SPConstants.CHANGETYPE);
      if (null == changeType) {
        LOGGER.log(Level.WARNING, "Unknown change type! Skipping... ");
        continue;
      } else if (changeType.equalsIgnoreCase("InvalidToken")) {
        String ct = list.getChangeTokenForWSCall();       
        throw new SharepointException(
            "Current change token [ " + ct + " ] of List [ " + list 
            + " ] has expired or is invalid. "
            + "State of the list will be reset to initiate a full crawl.");
      }

      final String itemId = change.getValue();
      if (null == itemId) {
        LOGGER.log(Level.WARNING, "Unknown ItemID for change type [ "
            + changeType + " ] Skipping... ");
        continue;
      }

      LOGGER.config("Received change type as: " + changeType);

      if (SPConstants.DELETE.equalsIgnoreCase(changeType) ||
          SPConstants.MOVE_AWAY.equalsIgnoreCase(changeType)) {
        if (FeedType.CONTENT_FEED != sharepointClientContext.getFeedType()) {
          // Delete feed processing is done only in case of
          // content feed
          LOGGER.fine("Ignoring change type: " + changeType
              + " since its applicable for content feed only");
          continue;
        }
        if (list.isInDeleteCache(itemId)) {
          // Check if all dependent ids are processed
          Set<String> depIds = list.getExtraIDs(itemId);
          if (depIds == null || depIds.size() == 1) {
          // We have already processed this.
          LOGGER.log(Level.WARNING, "skipping deleted ItemID [" + itemId
              + "] because it has been processed in previous batch traversal(s). listURL ["
              + list.getListURL() + " ]. ");
          continue;
          } else {
            LOGGER.log(Level.WARNING, "Deleted ItemID [" + itemId
                + "] is already processed but"
                + " dependent ids are still not processed form listURL ["
                + list.getListURL() + " ]. ");
          }
        }
        LOGGER.log(Level.INFO, "ItemID [" + itemId + "] has been deleted. "
            + "Delete feeds will be sent for this and all the dependent IDs. "
            + "listURL [" + list.getListURL() + " ] ");
        try {
          deletedIDs.addAll(list.getExtraIDs(itemId));
        } catch (final Exception e) {
          LOGGER.warning("Problem occured while getting the dependent IDs for deleted ID [ "
              + itemId + " ]. listURL [" + list.getListURL() + " ]. ");
        }
      }
      // Folder rename/restore are tracked only when the current
      // change token is being used for the first time while making WS
      // call hence, the check
      // null == list.getNextChangeTokenForSubsequectWSCalls()
      else if (SPConstants.RESTORE.equalsIgnoreCase(changeType)
          && null == list.getNextChangeTokenForSubsequectWSCalls()) {
        restoredIDs.add(itemId);
        LOGGER.log(Level.INFO, "ItemID [" + itemId + "] has been restored. "
            + "ADD feeds will be sent for this and all the dependent IDs. listURL ["
            + list.getListURL() + " ] ");
        // Since the item has been restored, it becomes a candidate for
        // deletion again. This has to be reflected here by removing
        // it from the delete cache.
        // FIXME The right way of handling this is to clear the delete
        // cache when a new change token is committed just like we do
        // for ListState.changedFolders
        list.removeFromDeleteCache(itemId);
      } else if (SPConstants.RENAME.equalsIgnoreCase(changeType)
          && null == list.getNextChangeTokenForSubsequectWSCalls()) {
        renamedIDs.add(itemId);
        LOGGER.log(Level.INFO, "ItemID [" + itemId + "] has been renamed. "
            + "ADD feeds will be sent for this and all the dependent IDs. listURL ["
            + list.getListURL() + " ] ");
      } else if (SPConstants.SYSTEM_UPDATE.equalsIgnoreCase(changeType)) {
        renamedIDs.add(itemId);
        LOGGER.log(Level.INFO, "ItemID [" + itemId
            + "] has been moved with change type as SystemUpdate. "
            + "Hence ADD feeds will be sent for this and all the dependent IDs. listURL ["
            + list.getListURL() + " ] ");
      }
    }

    if (saveChangeToken) {
      list.saveNextChangeTokenForWSCall(lastChangeToken);
    }
  }

  /**
   * Parses a typical &ltz:row..&gt node which are returned by SharePoint web
   * services for individual documents/listItems.
   *
   * @param listItem The one rs:rows node to be parsed.
   * @param list Base List
   * @param allWebs To Store the link sites.
   * @return the constructed {@link SPDocument} from the message element
   *         returned by the WS
   */
  public static SPDocument processListItemElement(
      final SharepointClientContext sharepointClientContext,
      final MessageElement listItem, final ListState list, 
      final Set<String> allWebs) {
    
    String fsObjType = listItem.getAttribute(SPConstants.OWS_FSOBJTYPE);
    fsObjType = Util.normalizeMetadataValue(fsObjType);
    LOGGER.log(Level.FINEST, "fsObjType [ " + fsObjType
        + " ]. ");
    boolean isFolder = (fsObjType!= null) && fsObjType.equals("1");
    boolean isFeedable = 
        isFeedableListItem(sharepointClientContext, listItem, list);
    boolean pushAcls = sharepointClientContext.isPushAcls();
    if (!isFeedable && (!isFolder || !pushAcls)) {
      LOGGER.warning(
          "List Item or Document is not yet published on SharePoint site, "
          + "hence discarding the ID [" + listItem.getAttribute(SPConstants.ID)
          + "] under the List/Document Library URL " + list.getListURL()
          + ", and its current version is "
          + listItem.getAttribute(SPConstants.MODERATION_STATUS));
      return null;
    }

    // Get all the required attributes.
    String fileref = listItem.getAttribute(SPConstants.FILEREF);
    if (fileref == null) {
      LOGGER.log(Level.WARNING, SPConstants.FILEREF
          + " is not found for one of the items in list [ " + list.getListURL()
          + " ]. ");
    } else {
      fileref = fileref.substring(fileref.indexOf(SPConstants.HASH) + 1);
    }

    final String lastModified = listItem.getAttribute(SPConstants.MODIFIED);
    String strObjectType = listItem.getAttribute(SPConstants.CONTENTTYPE);
    String fileSize = listItem.getAttribute(SPConstants.FILE_SIZE_DISPLAY);

    if (fileSize == null) {
      // Check with the other file size attribute as back-up
      fileSize = listItem.getAttribute(SPConstants.FILE_SIZE);
    }

    String author = listItem.getAttribute(SPConstants.EDITOR);
    if (author == null) {
      author = listItem.getAttribute(SPConstants.AUTHOR);
    }
    String docId = listItem.getAttribute(SPConstants.ID);
    Iterator<?> itAttrs = listItem.getAllAttributes();

    // Start processing based on the above read attributes.

    // STEP1: Process link sites
    if (list.isLinkSite() && (allWebs != null)) {
      String linkSiteURL = listItem.getAttribute(SPConstants.URL);// e.g.
      // http://www.abc.com,
      // abc
      // site"
      if (linkSiteURL == null) {
        LOGGER.log(Level.WARNING, "Unable to get the link URL");
      } else {
        // filter out description
        int comma = linkSiteURL.indexOf(",");
        if (comma != -1) {
          linkSiteURL = linkSiteURL.substring(0, comma); 
        }
        LOGGER.config("Linked Site / Site Directory URL :" + linkSiteURL);
        if (sharepointClientContext.isIncludedUrl(linkSiteURL, LOGGER)) {
          allWebs.add(linkSiteURL);
        }
      }
    }  

    if (docId == null) {
      LOGGER.log(Level.WARNING, SPConstants.ID
          + " is not found for one of the items in list [ " + list.getListURL()
          + " ]. ");
      return null;
    }

    final StringBuffer url = new StringBuffer();
    String displayUrl = null;
    final String urlPrefix = 
            Util.getWebApp(sharepointClientContext.getSiteURL());
    // fsobjtype = 1 indicates this is a folder.
    // (Applicable for Default Folder content Type as well as for
    // Custom folder content type).
    // Checking content type = folder might not be sufficient 
    // as there is a possibility of custom folder content type
    // TODO Check all the CAML queries checking 
    // for Content Type = folder and change it to FSObjType = 1. 
    if (isFolder) {
      // This is a Folder Item.
      // For folder item URL will be calculated as Web Url + filref for folder
      url.setLength(0);
      url.append(urlPrefix);
      url.append(SPConstants.SLASH);
      url.append(fileref);
      displayUrl = url.toString();    
    } else if (list.isDocumentLibrary()) {
      if (fileref == null) {
        return null;
      }
      /*
       * An example of ows_FileRef is 1;#unittest/Shared SPDocuments/sync.doc We
       * need to get rid of 1;# so that the document URL can be constructed.
       */   
      url.setLength(0);
      url.append(urlPrefix);
      url.append(SPConstants.SLASH);
      url.append(fileref);
      displayUrl = url.toString();
      if (list.isInfoPathLibrary()) {
        url.append("?");
        url.append(SPConstants.NOREDIRECT);
      }
    } else {
      // TODO this is not a correct implementation. 
      // This is not considering any customizations done on SharePoint list.
      // Ideally need to get Default display form URL for 
      // List and construct URL.
      // Due to use of OOB SharePoint web service,
      // this information is not available in ListState.
      // Custom web service might be the approach.
      // SPList.DefaultDisplayFormUrl can be used to get this information.
      url.setLength(0);
      url.append(urlPrefix);
      url.append(SPConstants.SLASH);
      // Below checks are required to form appropriate URLs in-Case of blog site
      // Object types
      if (list.getBaseTemplate().equals(SPConstants.BT_CATEGORIES)) {
        // Categories items of blog site the URL should be like:
        // http://server:port/blogsiteName/Lists/Categories/ViewCategory.aspx?ID=1
        url.append(list.getListConst() + SPConstants.VIEWCATEGORY);
      } else if (list.getBaseTemplate().equals(SPConstants.BT_POSTS)) {
        // Posts items of blog site the URL should be like:
        // http://server:port/blogsiteName/Lists/Posts/ViewPost.aspx?ID=1
        url.append(list.getListConst() + SPConstants.VIEWPOST);
      } else if (list.getBaseTemplate().equals(SPConstants.BT_COMMENTS)) {
        // Comments items of blog site the URL should be like:
        // http://server:port/blogsiteName/Lists/Comments/ViewComment.aspx?ID=1
        url.append(list.getListConst() + SPConstants.VIEWCOMMENT);
      } else {
        url.append(list.getListConst() + SPConstants.DISPFORM);
      }
      url.append(docId);
      displayUrl = url.toString();
      if (list.isInfoPathLibrary()) {
        url.append("&");
        url.append(SPConstants.NOREDIRECT);
      }
    }

    LOGGER.config("ListItem URL: " + url);
    if (!sharepointClientContext.isIncludedUrl(url.toString(), LOGGER)) {
      return null;
    }

    if (strObjectType == null) {
      if (list.isDocumentLibrary()) {
        strObjectType = SPConstants.DOCUMENT;
      } else {
        strObjectType = SPConstants.OBJTYPE_LIST_ITEM;
      }
    }

    if (author == null) {
      author = SPConstants.NO_AUTHOR;
    } else {
      author = author.substring(author.indexOf(SPConstants.HASH) + 1); // e.g.1073741823;#System
      // Account
    }

    Calendar calMod;
    try {
      LOGGER.config("The ISO 8601 date received from WS for last modified is: "
          + lastModified
          + " It will be stored in the snapshot and used for change detection.");
      calMod = DateUtil.iso8601ToCalendar(lastModified);
    } catch (final ParseException pe) {
      LOGGER.warning("Unable to parse the document's last modified ("
          + lastModified + ") date value. Using parent's last modified date.");
      calMod = list.getLastModCal();
    }

    if (FeedType.CONTENT_FEED == sharepointClientContext.getFeedType()) {
      docId = list.getListURL() + SPConstants.DOC_TOKEN + docId;      
    }
    SPDocument doc = new SPDocument(docId, url.toString(), calMod, author,
        strObjectType, list.getParentWebState().getTitle(),
        sharepointClientContext.getFeedType(), list.getParentWebState().getSharePointType());
    doc.setFileref(fileref);
    doc.setDisplayUrl(displayUrl);
    doc.setParentList(list);
    boolean skipAttributes = isFolder && !isFeedable;
    doc.setEmptyDocument(skipAttributes);
    if (skipAttributes) {
      LOGGER.log(Level.FINE, "FeedType = [" 
          + sharepointClientContext.getFeedType() + "] isInitialTraversal = ["
          + sharepointClientContext.isInitialTraversal() + "]");
      if ((sharepointClientContext.getFeedType() == FeedType.METADATA_URL_FEED)
          || sharepointClientContext.isInitialTraversal()) {        
        doc.setDocumentType(DocumentType.ACL);
      }
    }

    if (fileSize != null && !fileSize.equals("")) {
      try {
        doc.setFileSize(Integer.parseInt(fileSize));
      } catch (NumberFormatException nfe) {
        // Just log the message in case of errors.
        if (LOGGER.isLoggable(Level.FINEST)) {
          LOGGER.log(Level.FINEST, "Problems while parsing the file size attribute",
              nfe.getMessage());
        }
      }
    } else if (LOGGER.isLoggable(Level.FINER)) {
      // Just log for any doc level debugging purposes
      LOGGER.finer("No file size attribute retrieved for document : "
          + doc.getUrl());
    }

    // iterate through all the attributes get the atribute name and value
    if (itAttrs != null && !skipAttributes) {
      while (itAttrs.hasNext()) {
        final Object oneAttr = itAttrs.next();
        if (oneAttr != null) {
          String strAttrName = oneAttr.toString();
          if ((strAttrName != null) && (!strAttrName.trim().equals(""))) {
            String strAttrValue = listItem.getAttribute(strAttrName);
            // Apply the well known rules of name resolution and
            // normalizing the values
            strAttrName = Util.normalizeMetadataName(strAttrName);
            strAttrValue = Util.normalizeMetadataValue(strAttrValue);
            if (sharepointClientContext.isIncludeMetadata(strAttrName)) {
              doc.setAttribute(strAttrName, strAttrValue);
            } else {
              LOGGER.log(Level.FINE, "Excluding metadata name [ " + strAttrName
                  + " ], value [ " + strAttrValue + " ] for doc URL [ " + url
                  + " ]. ");
            }
          }
        }
      }
    }

    if (FeedType.CONTENT_FEED == sharepointClientContext.getFeedType()) {
      try {
        final int id = Integer.parseInt(Util.getOriginalDocId(docId,
            sharepointClientContext.getFeedType()));
        if (id > list.getBiggestID()) {
          list.setBiggestID(id);
        }
      } catch (final Exception e) {
        // Eatup the exception. This was just to ensure that this is a
        // list item. If it is not, then it will ba a case of list.
      }
    }
    return doc;
  }

   /**
   * Gets whether the given list item should be indexed.
   *
   * @param ctx the client context to check the
   *     {@code feedUnPublishedDocuments} configuration property
   * @param me the XML for the list item
   * @param list the list the list item is in, used for logging
   * @return {@code true} if the list item should be indexed, or
   *     {@code false} if it should not be
   */
  public static boolean isFeedableListItem(SharepointClientContext ctx,
      MessageElement me, ListState list) {
    return isFeedableListItem(ctx.isFeedUnPublishedDocuments(),
        me, list.getListURL());
  }

  @VisibleForTesting
  static boolean isFeedableListItem(boolean isFeedUnpublishedDocuments,
      MessageElement me, String listUrl) {
    if (isFeedUnpublishedDocuments) {
      return true;
    } else {
      String docVersion = me.getAttribute(SPConstants.MODERATION_STATUS);
      if (Strings.isNullOrEmpty(docVersion)) {
        LOGGER.warning(SPConstants.MODERATION_STATUS
            + " is not found for the ID ["
            + me.getAttribute(SPConstants.ID)
            + "] under the List/Document Library URL " + listUrl);
        return true;
      } else {
        return docVersion.equals(SPConstants.DocVersion.APPROVED.toString());
      }
    }
  }

  /**
   * An interface that contains the query information needed to make Sharepoint
   * SOAP queries.
   */
  public interface SPQueryInfo {
    /**
     * Returns the {@link org.apache.axis.message.MessageElement} array that is used
     * with GetListItemsQuery and GetListItemChangesSinceTokenQuery.
     *
     * @return a MessageElement array, with usually a single entry
     */
    MessageElement[] getQuery() throws Exception;

    /**
     * Returns the {@link org.apache.axis.message.MessageElement} array that is used
     * in the view fields with GetListItemsViewFields and 
     * GetListItemChangesSinceTokenViewFields.
     *
     * @return a MessageElement array, with usually a single entry
     */
    MessageElement[] getViewFields() throws Exception;

    /**
     * Returns the {@link org.apache.axis.message.MessageElement} array that is used
     * in the query options with GetListItemsQueryOptions and 
     * GetListItemChangesSinceTokenQueryOptions.
     *
     * @return a MessageElement array, with usually a single entry
     */
    MessageElement[] getQueryOptions() throws Exception;
  }
}
