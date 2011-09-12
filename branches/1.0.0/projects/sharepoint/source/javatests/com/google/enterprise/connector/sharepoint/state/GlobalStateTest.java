// Copyright 2007 Google Inc.

package com.google.enterprise.connector.sharepoint.state;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Iterator;

import junit.framework.TestCase;

import org.joda.time.DateTime;

import com.google.enterprise.connector.sharepoint.Util;
import com.google.enterprise.connector.sharepoint.client.SPDocument;
import com.google.enterprise.connector.sharepoint.client.SharepointException;


/**
 * Tests the GlobalState object. In most cases, it creates the object,
 * dumps it to XML file 1, loads from XML file 1, dumps that to XML file 2,
 * and asserts that XML file 1 is identical to XML file 2.
 * @author amit_kagrawal
 */

public class GlobalStateTest extends TestCase {

  private GlobalState state;
  
  /**
   * Directory where the GlobalState file will be stored.
   * Replace this with a suitable temporary directory if not running on 
   * a Linux or Unix-like system.
   */
  private static final String TMP_DIR = "c:";
  
  public final void setUp() {
    state = new GlobalState(TMP_DIR);
  }

  /**
   * Test basic functionality: create a GlobalState, save it to XML,
   * load another from XML, save THAT to XML, and verify that the XML
   * is the same.
   */
  public final void testBasic() throws SharepointException {
    // We feed in specific dates for repeatability of the test:
    DateTime time1 = Util.parseDate("20070504T142925.499+0530");
    DateTime time2 = Util.parseDate("20070504T142925.772+0530");
    ListState list1 = null;
    ListState list2 = null;

    //creating list states for global state
    list1 = state.makeListState("foo", time1);
    list2 = state.makeListState("bar", time2);

    try {
      String output = state.getStateXML();// get the xml representation of the in-memory global state
      String expected1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
    	  				 +"<state>\r\n"
    	  				 +"<com.google.enterprise.connector.sharepoint.state.ListState id=\"foo\">\r\n"
    	  				 +"<lastMod>20070504T142925.499+0530</lastMod>\r\n"
    	  				 +"<URL/>\r\n"
    	  				 +"</com.google.enterprise.connector.sharepoint.state.ListState>\r\n"
    	  				 +"<com.google.enterprise.connector.sharepoint.state.ListState id=\"bar\">\r\n"
    	  				 +"<lastMod>20070504T142925.772+0530</lastMod>\r\n"
    	  				 +"<URL/>\r\n"
    	  				 +"</com.google.enterprise.connector.sharepoint.state.ListState>\r\n"
    	  				 +"</state>\r\n";

      assertEquals(expected1, output);//check if the state is as expected
      state.saveState();//save the state to disk.. forms TMP_DIR\Sharepoint_state.xml file
      GlobalState state2 = null;

      state2 = new GlobalState(TMP_DIR);
      state2.loadState(); // load from the old GlobalState's XML
      String output2 = state2.getStateXML();

      assertEquals(output, output2);// output of the new GlobalState should match the old one's:

    } catch (SharepointException e1) {
      fail(e1.toString());
    }

    state.startRecrawl();//set exist all false
    state.updateList(list1, list1.getLastMod());//set exists for list1
    state.endRecrawl();//list2 will be removed from global state

    // list1 should still be there, list2 should be gone
    StatefulObject obj = (StatefulObject) state.keyMap.get("bar");
    assertNull(obj);
    obj = (StatefulObject) state.keyMap.get("foo");
    assertNotNull(obj);
  }

  public final void testLastDocCrawled() {
    DateTime time1 = Util.parseDate("20070504T144419.403-0700");
    DateTime time2 = Util.parseDate("20070504T144419.867-0700");
    ListState list1 = null, list2 = null;
    try {
      list1 = state.makeListState("foo", time1);
      list2 = state.makeListState("bar", time2);
      SPDocument doc1 = new SPDocument("id1", "url1", 
          new GregorianCalendar(2007, 1, 1));
      list1.setLastDocCrawled(doc1);
      state.setCurrentList(list1);
      SPDocument doc2 = new SPDocument("id2", "url2", 
          new GregorianCalendar(2007, 1, 2));
      list2.setLastDocCrawled(doc2);
      String expected1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" 
    	  +"<state>\r\n" 
    	  +"<current id=\"foo\" type=\"com.google.enterprise.connector.sharepoint.state.ListState\"/>\r\n" 
    	  +"<com.google.enterprise.connector.sharepoint.state.ListState id=\"foo\">\r\n" 
    	  +"<lastMod>20070505T031419.403+0530</lastMod>\r\n" 
    	  +"<URL/>\r\n" 
    	  +"<lastDocCrawled>\r\n" 
    	  +"<document id=\"id1\">\r\n" 
    	  +"<lastMod>20070201T000000.000+0530</lastMod>\r\n" 
    	  +"<url>url1</url>\r\n" 
    	  +"</document>\r\n" 
    	  +"</lastDocCrawled>\r\n" 
    	  +"</com.google.enterprise.connector.sharepoint.state.ListState>\r\n" 
    	  +"<com.google.enterprise.connector.sharepoint.state.ListState id=\"bar\">\r\n" 
    	  +"<lastMod>20070505T031419.867+0530</lastMod>\r\n" 
    	  +"<URL/>\r\n" 
    	  +"<lastDocCrawled>\r\n" 
    	  +"<document id=\"id2\">\r\n" 
    	  +"<lastMod>20070202T000000.000+0530</lastMod>\r\n" 
    	  +"<url>url2</url>\r\n" 
    	  +"</document>\r\n" 
    	  +"</lastDocCrawled>\r\n" 
    	  +"</com.google.enterprise.connector.sharepoint.state.ListState>\r\n" 
    	  +"</state>\r\n";
      String output = state.getStateXML();
      System.out.println(output);
      assertEquals(expected1, output);
      state.saveState();
      GlobalState state2 = null;

      state2 = new GlobalState(TMP_DIR);
      state2.loadState(); // load from the old GlobalState's XML
      // output of the new GlobalState should match the old one's:
      String output2 = state2.getStateXML();
      assertEquals(output, output2);
    } catch (SharepointException e) {
      e.printStackTrace();
      fail();
    }   
  }

  /**
   * tests that we can persistify the crawl queue, and reload from it.
   */
  public final void testCrawlQueue() {
    DateTime time1 = Util.parseDate("20070504T144419.403-0700");
    DateTime time2 = Util.parseDate("20070504T144419.867-0700");
    ListState list1 = null, list2 = null;
    try {
      list1 = state.makeListState("foo", time1);
      list2 = state.makeListState("bar", time2);
      SPDocument doc1 = new SPDocument("id1", "url1", 
          new GregorianCalendar(2007, 1, 1));
      list1.setLastDocCrawled(doc1);
      state.setCurrentList(list1);

      SPDocument doc2 = new SPDocument("id2", "url2", 
          new GregorianCalendar(2007, 1, 2));
      list2.setLastDocCrawled(doc2);

      // make a crawl queue & store it in our "current" ListState
      ArrayList docTree = new ArrayList();
      SPDocument doc3 = new SPDocument("id3", "url3", 
          new GregorianCalendar(2007, 1, 3));
      docTree.add(doc3);
      SPDocument doc4 = new SPDocument("id4", "url4", 
          new GregorianCalendar(2007, 1, 4));
      docTree.add(doc4);
      list1.setCrawlQueue(docTree);

      String output = state.getStateXML();
      String expected1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" 
    	  +"<state>\r\n" 
    	  +"<current id=\"foo\" type=\"com.google.enterprise.connector.sharepoint.state.ListState\"/>\r\n" 
    	  +"<com.google.enterprise.connector.sharepoint.state.ListState id=\"foo\">\r\n" 
    	  +"<lastMod>20070505T031419.403+0530</lastMod>\r\n" 
    	  +"<URL/>\r\n" 
    	  +"<lastDocCrawled>\r\n" 
    	  +"<document id=\"id1\">\r\n" 
    	  +"<lastMod>20070201T000000.000+0530</lastMod>\r\n" 
    	  +"<url>url1</url>\r\n" 
    	  +"</document>\r\n" 
    	  +"</lastDocCrawled>\r\n" 
    	  +"<crawlQueue>\r\n" 
    	  +"<document id=\"id3\">\r\n" 
    	  +"<lastMod>20070203T000000.000+0530</lastMod>\r\n" 
    	  +"<url>url3</url>\r\n" 
    	  +"</document>\r\n" 
    	  +"<document id=\"id4\">\r\n" 
    	  +"<lastMod>20070204T000000.000+0530</lastMod>\r\n" 
    	  +"<url>url4</url>\r\n" 
    	  +"</document>\r\n" 
    	  +"</crawlQueue>\r\n" 
    	  +"</com.google.enterprise.connector.sharepoint.state.ListState>\r\n" 
    	  +"<com.google.enterprise.connector.sharepoint.state.ListState id=\"bar\">\r\n" 
    	  +"<lastMod>20070505T031419.867+0530</lastMod>\r\n" 
    	  +"<URL/>\r\n" 
    	  +"<lastDocCrawled>\r\n" 
    	  +"<document id=\"id2\">\r\n" 
    	  +"<lastMod>20070202T000000.000+0530</lastMod>\r\n" 
    	  +"<url>url2</url>\r\n" 
    	  +"</document>\r\n" 
    	  +"</lastDocCrawled>\r\n" 
    	  +"</com.google.enterprise.connector.sharepoint.state.ListState>\r\n" 
    	  +"</state>\r\n";
      System.out.println(output);
      assertEquals(expected1, output);
      state.saveState();
      GlobalState state2 = null;

      state2 = new GlobalState(TMP_DIR);
      state2.loadState(); // load from the old GlobalState's XML
      // output of the new GlobalState should match the old one's:
      String output2 = state2.getStateXML();
      assertEquals(output, output2);
    } catch (SharepointException e) {
      e.printStackTrace();
      fail();
    }      
  }

  /**
   * Utility for testCircularIterators():  make sure the iterator returns
   * the same set of items as list.
   * @param iter  iterator to be tested
   * @param list  array of the expected results
   */
  void verifyIterator(Iterator iter, ListState[] expected) {
    for (int i = 0; i < expected.length; i++) {
      assertTrue(iter.hasNext());
      ListState found = (ListState) iter.next();
      assertEquals(found, expected[i]);
    }
    assertFalse(iter.hasNext());
  }
  
  /**
   * Make sure that the getCircularIterator() call works properly (since, if it
   * doesn't, the connector will fail to pick up new or changed SharePoint.
   * documents)
   */
  public void testCircularIterators() throws SharepointException {
    DateTime time1 = Util.parseDate("20070504T144419.403-0700");
    DateTime time2 = Util.parseDate("20070505T144419.867-0700");
    DateTime time3 = Util.parseDate("20070506T144419.867-0700");
    ListState list1 = null, list2 = null, list3 = null;

    list1 = state.makeListState("foo", time1);
    list2 = state.makeListState("bar", time2);
    list3 = state.makeListState("baz", time3);
    ListState[] arr1 = {list1, list2, list3};
    ListState[] arr2 = {list2, list3, list1};
    ListState[] arr3 = {list3, list1, list2};
    verifyIterator(state.getCircularIterator(), arr1);
    state.setCurrentList(list2);
    verifyIterator(state.getCircularIterator(), arr2);
    state.setCurrentList(list3);
    verifyIterator(state.getCircularIterator(), arr3);
  }
}