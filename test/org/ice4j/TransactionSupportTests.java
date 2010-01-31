/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j;

import java.net.*;
import java.util.*;
import java.util.logging.*;

import junit.framework.*;

import org.ice4j.message.*;
import org.ice4j.stack.*;

/**
 * Test how client and server behave, how they recognize/adopt messages and
 * how they both handle retransmissions (i.e. client transactions should make
 * them and server transactions should hide them)
 *
 * @author Emil Ivov
 */
public class TransactionSupportTests extends TestCase
{
    /**
     * The client address we use for this test.
     */
    TransportAddress clientAddress
        = new TransportAddress("127.0.0.1", 5216, Transport.UDP);

    /**
     * The client address we use for this test.
     */
    TransportAddress serverAddress
        = new TransportAddress("127.0.0.1", 5255, Transport.UDP);

    /**
     * The socket the client uses in this test.
     */
    DatagramSocket clientSock = null;

    /**
     * The socket the server uses in this test.
     */
    DatagramSocket serverSock = null;

    /**
     * The request we send in this test.
     */
    Request  bindingRequest = null;

    /**
     * The response we send in this test.
     */
    Response bindingResponse = null;

    PlainRequestCollector requestCollector = null;
    PlainResponseCollector responseCollector = null;

    protected void setUp() throws Exception
    {
        super.setUp();

        clientSock = new DatagramSocket(clientAddress);
        serverSock = new DatagramSocket(serverAddress);

        StunStack.getInstance().addSocket(clientSock);
        StunStack.getInstance().addSocket(serverSock);

        bindingRequest = MessageFactory.createBindingRequest();
        bindingResponse = MessageFactory.createBindingResponse(
            clientAddress, clientAddress, serverAddress);

        requestCollector = new PlainRequestCollector();
        responseCollector = new PlainResponseCollector();

        System.setProperty("org.ice4j.PROPAGATE_RECEIVED_RETRANSMISSIONS",
                           "false");
        System.setProperty("org.ice4j.KEEP_CLIENT_TRANS_AFTER_A_RESPONSE",
                           "false");
        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS",
                           "");
        System.setProperty("org.ice4j.MAX_WAIT_INTERVAL",
                           "");
        System.setProperty("org.ice4j.ORIGINAL_WAIT_INTERVAL",
                           "");


    }

    protected void tearDown() throws Exception
    {
        StunStack.getInstance().removeSocket(clientAddress);
        StunStack.getInstance().removeSocket(serverAddress);

        clientSock.close();
        serverSock.close();

        requestCollector = null;
        responseCollector = null;

        System.setProperty("org.ice4j.PROPAGATE_RECEIVED_RETRANSMISSIONS",
                           "false");
        System.setProperty("org.ice4j.KEEP_CLIENT_TRANS_AFTER_A_RESPONSE",
                           "false");
        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS",
                           "");
        System.setProperty("org.ice4j.MAX_WAIT_INTERVAL",
                           "");
        System.setProperty("org.ice4j.ORIGINAL_WAIT_INTERVAL",
                           "");

        super.tearDown();
    }

    /**
     * Test that requests are retransmitted if no response is received
     *
     * @throws java.lang.Exception upon any failure
     */
    public void testClientRetransmissions() throws Exception
    {
        String oldRetransValue = System.getProperty(
                                            "org.ice4j.MAX_RETRANSMISSIONS");
        String oldMaxWaitValue = System.getProperty(
                                            "org.ice4j.MAX_WAIT_INTERVAL");

        System.setProperty("org.ice4j.MAX_WAIT_INTERVAL", "100");
        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS", "2");

        //prepare to listen
        System.setProperty("org.ice4j.PROPAGATE_RECEIVED_RETRANSMISSIONS",
                           "true");

        StunStack.getInstance()
            .addRequestListener(serverAddress, requestCollector);
        //send
        StunStack.getInstance().sendRequest(bindingRequest,
                         serverAddress, clientAddress, responseCollector);
        //wait for retransmissions
        Thread.sleep(1000);

        //verify
        Vector<StunMessageEvent> reqs
            = requestCollector.getRequestsForTransaction(
                                bindingRequest.getTransactionID());
        assertTrue("No retransmissions of the request have been received",
            reqs.size() > 1);
        assertTrue("The binding request has not been retransmitted enough!",
            reqs.size() >= 3);

        //restore the retransmissions prop in case others are counting on
        //defaults.
        if(oldRetransValue != null)
            System.getProperty( "org.ice4j.MAX_RETRANSMISSIONS",
                                oldRetransValue);
        else
            System.clearProperty("org.ice4j.MAX_RETRANSMISSIONS");

        if(oldMaxWaitValue != null)
            System.getProperty( "org.ice4j.MAX_WAIT_INTERVAL",
                                oldRetransValue);
        else
            System.clearProperty("org.ice4j.MAX_WAIT_INTERVAL");


    }

    /**
     * Make sure that retransmissions are not seen by the server user and that
     * it only gets a single request.
     *
     * @throws Exception if anything goes wrong.
     */
    public void testServerRetransmissionHiding() throws Exception
    {
        String oldRetransValue = System.getProperty(
                "org.ice4j.MAX_RETRANSMISSIONS");
        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS", "2");
        //prepare to listen
        StunStack.getInstance().addRequestListener(
                        serverAddress, requestCollector);
        //send
        StunStack.getInstance().sendRequest(bindingRequest,
              serverAddress, clientAddress, responseCollector);

        //wait for retransmissions
        Thread.sleep(1000);

        //verify
        Vector<StunMessageEvent> reqs
            = requestCollector.getRequestsForTransaction(
                bindingRequest.getTransactionID());

        assertTrue(
            "Retransmissions of a binding request were propagated "
            + "to the server", reqs.size() <= 1 );

        //restore the retransmissions prop in case others are counting on
        //defaults.
        if(oldRetransValue != null)
            System.getProperty( "org.ice4j.MAX_RETRANSMISSIONS",
                                oldRetransValue);
        else
            System.clearProperty("org.ice4j.MAX_RETRANSMISSIONS");
    }

    /**
     * Makes sure that once a request has been answered by the server,
     * retransmissions of this request are not propagated to the UA and are
     * automatically handled with a retransmission of the last seen response
     *
     * @throws Exception if we screw up.
     */
    public void testServerResponseRetransmissions() throws Exception
    {
        String oldRetransValue = System.getProperty(
            "org.ice4j.MAX_RETRANSMISSIONS");
        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS", "2");
        System.setProperty("org.ice4j.MAX_WAIT_INTERVAL", "100");

        //prepare to listen
        System.setProperty("org.ice4j.KEEP_CLIENT_TRANS_AFTER_A_RESPONSE",
                           "true");
        StunStack.getInstance().addRequestListener(
                        serverAddress, requestCollector);
        //send
        StunStack.getInstance().sendRequest(
            bindingRequest, serverAddress,
                clientAddress, responseCollector);

        //wait for the message to arrive
        requestCollector.waitForRequest();

        Vector<StunMessageEvent> reqs = requestCollector
            .getRequestsForTransaction( bindingRequest.getTransactionID());

        StunMessageEvent evt = ((StunMessageEvent)reqs.get(0));

        byte[] tid = evt.getMessage().getTransactionID();
        StunStack.getInstance().sendResponse(
                        tid, bindingResponse, serverAddress, clientAddress);

        //wait for retransmissions
        Thread.sleep(500);

        //verify that we received a fair number of retransmitted responses.
        assertTrue(
            "There were too few retransmissions of a binding response: "
                        +responseCollector.receivedResponses.size(),
            responseCollector.receivedResponses.size() < 3 );

        //restore the retransmissions prop in case others are counting on
        //defaults.
        if(oldRetransValue != null)
            System.getProperty( "org.ice4j.MAX_RETRANSMISSIONS",
                                oldRetransValue);
        else
            System.clearProperty("org.ice4j.MAX_RETRANSMISSIONS");

        System.clearProperty("org.ice4j.MAX_WAIT_INTERVAL");
    }

    /**
     * A (very) weak test, verifying that transaction IDs are unique.
     * @throws Exception in case we feel like it.
     */
    public void testUniqueIDs() throws Exception
    {
        StunStack.getInstance().addRequestListener(
                            serverAddress, requestCollector);
        //send req 1
        StunStack.getInstance().sendRequest(bindingRequest,
            serverAddress, clientAddress, responseCollector);

        //wait for retransmissions
        requestCollector.waitForRequest();

        Vector<StunMessageEvent> reqs1 = requestCollector
            .getRequestsForTransaction( bindingRequest.getTransactionID());

        StunMessageEvent evt1 = ((StunMessageEvent)reqs1.get(0));

        //send a response to make the other guy shut up
        byte[] tid = evt1.getMessage().getTransactionID();
        StunStack.getInstance().sendResponse(tid,
                    bindingResponse, serverAddress, clientAddress);

        //send req 2
        StunStack.getInstance().sendRequest(bindingRequest,
                        serverAddress, clientAddress, responseCollector);

        //wait for retransmissions
        Thread.sleep(1000);

        Vector<StunMessageEvent> reqs2
            = requestCollector.getRequestsForTransaction(
                bindingRequest.getTransactionID());

        StunMessageEvent evt2 = ((StunMessageEvent)reqs2.get(0));

        assertFalse("Consecutive requests were assigned the same transaction id",
            Arrays.equals( evt1.getMessage().getTransactionID(),
                           evt2.getMessage().getTransactionID()));
    }

    public void testClientTransactionMaxRetransmisssionsConfigurationParameter()
        throws Exception
    {
        //MAX_RETRANSMISSIONS

        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS",
                           "2");
        //make sure we see retransmissions so that we may count them
        System.setProperty("org.ice4j.PROPAGATE_RECEIVED_RETRANSMISSIONS",
                           "true");
        StunStack.getInstance().addRequestListener(
                        serverAddress, requestCollector);
        //send
        StunStack.getInstance().sendRequest(
            bindingRequest, serverAddress, clientAddress, responseCollector);
        //wait for retransmissions
        Thread.sleep(1600);

        //verify
        Vector<StunMessageEvent> reqs
            = requestCollector.getRequestsForTransaction(
                bindingRequest.getTransactionID());

        assertTrue("No retransmissions of the request have been received",
            reqs.size() > 1);
        assertEquals(
            "The MAX_RETRANSMISSIONS param was not taken into account!",
            reqs.size(),
            3);

    }

    public void testMinWaitIntervalConfigurationParameter()
        throws Exception
    {
        //MAX_RETRANSMISSIONS
        System.setProperty("org.ice4j.ORIGINAL_WAIT_INTERVAL",
                           "50");
        //make sure we see retransmissions so that we may count them
        System.setProperty("org.ice4j.PROPAGATE_RECEIVED_RETRANSMISSIONS",
                           "true");
        StunStack.getInstance().addRequestListener(
                        serverAddress, requestCollector);
        //send
        StunStack.getInstance().sendRequest(bindingRequest,
            serverAddress, clientAddress, responseCollector);

        //wait a while
        requestCollector.waitForRequest();

        //verify
        Vector reqs = requestCollector.getRequestsForTransaction(
                                bindingRequest.getTransactionID());
        assertTrue("A retransmissions of the request was sent too early",
            reqs.size() < 2);

        //wait for a send
        Thread.sleep(110);

        reqs = requestCollector.getRequestsForTransaction(
                                bindingRequest.getTransactionID());

        //verify
        assertEquals("A retransmissions of the request was not sent",
                     2, reqs.size());
    }

    public void testMaxWaitIntervalConfigurationParameter()
        throws Exception
    {
        //MAX_RETRANSMISSIONS
        System.setProperty("org.ice4j.MAX_WAIT_INTERVAL",
                           "100");
        //make sure we see retransmissions so that we may count them
        System.setProperty("org.ice4j.PROPAGATE_RECEIVED_RETRANSMISSIONS",
                           "true");
        System.setProperty("org.ice4j.MAX_RETRANSMISSIONS",
                           "11");
        StunStack.getInstance()
            .addRequestListener(serverAddress, requestCollector);
        //send
        StunStack.getInstance().sendRequest(bindingRequest,
            serverAddress, clientAddress, responseCollector);

        //wait a while
        Thread.sleep(1200);

        //verify
        Vector<StunMessageEvent> reqs
            = requestCollector.getRequestsForTransaction(
                                bindingRequest.getTransactionID());
        assertEquals("Not all retransmissions were made for the expected period "
                   +"of time",
                   12,
                   reqs.size());

        //wait for a send
        Thread.sleep(1800);

        //verify
        reqs = requestCollector.getRequestsForTransaction(
                                bindingRequest.getTransactionID());
        assertEquals("A retransmissions of the request was sent, while not "
                    +"supposed to",
                    12,
                    reqs.size());
    }

    private class PlainRequestCollector implements RequestListener{
        private Vector<StunMessageEvent> receivedRequestsVector
                                            = new Vector<StunMessageEvent>();

        public void requestReceived(StunMessageEvent evt)
        {
            synchronized(this)
            {
                receivedRequestsVector.add(evt);
                notifyAll();
            }
        }

        /**
         * Only return requests from the specified tran because we might have
         * capture others too.
         *
         * @param tranid the transaction that we'd like to get requests for.
         *
         * @return a Vector containing all request that we have received and
         * that match <tt>tranid</tt>.
         */
        public Vector<StunMessageEvent> getRequestsForTransaction(byte[] tranid)
        {
            Vector<StunMessageEvent> newVec = new Vector<StunMessageEvent>();

            Iterator<StunMessageEvent> reqsIter
                                    = receivedRequestsVector.iterator();

            while(reqsIter.hasNext())
            {
                StunMessageEvent evt = (StunMessageEvent)reqsIter.next();
                Message msg = evt.getMessage();
                if( Arrays.equals(tranid, msg.getTransactionID()))
                    newVec.add(evt);
            }

            return newVec;
        }

        public void waitForRequest()
        {
            synchronized(this)
            {
                try
                {
                    wait(50);
                }
                catch (InterruptedException e)
                {}
            }
        }
    }

    private class PlainResponseCollector implements ResponseCollector{

        public Vector<Object> receivedResponses = new Vector<Object>();

        public void processResponse(StunMessageEvent responseEvt)
        {
            receivedResponses.add(responseEvt);
        }

        public void processTimeout()
        {
            receivedResponses.add(new String("timeout"));
        }

    }
    /*
    public TransactionSupportTests(String name)
    {
        super(name);
    }
    public static Test suite()
    {
        TestSuite suite = new TestSuite();
        suite.addTest(new TransactionSupportTests(
            "testMaxWaitIntervalConfigurationParameter"));
        return suite;
    }
    */
}
