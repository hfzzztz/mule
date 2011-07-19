/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.xmpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jivesoftware.smack.packet.Message;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;
import org.mule.module.client.MuleClient;
import org.mule.tck.functional.FunctionalTestComponent;
import org.mule.transport.xmpp.JabberSender.Callback;
import org.mule.util.concurrent.Latch;

public class XmppMessageAsyncTestCase extends AbstractXmppTestCase
{
    protected static final long JABBER_SEND_THREAD_SLEEP_TIME = 1000;
    private static final String RECEIVE_SERVICE_NAME = "receiveFromJabber";

    private CountDownLatch latch = new CountDownLatch(1);

    public XmppMessageAsyncTestCase(ConfigVariant variant, String configResources)
    {
        super(variant, configResources);
    }

    @Parameters
    public static Collection<Object[]> parameters()
    {
        return Arrays.asList(new Object[][]{
            {ConfigVariant.SERVICE,
                AbstractXmppTestCase.COMMON_CONFIG + "," + "xmpp-message-async-config-service.xml"},
            {ConfigVariant.FLOW,
                AbstractXmppTestCase.COMMON_CONFIG + "," + "xmpp-message-async-config-flow.xml"}});
    }

    @Override
    protected void configureJabberClient(JabberClient client)
    {
        client.setSynchronous(false);
        client.setMessageLatch(latch);
    }

    @Test
    public void testDispatch() throws Exception
    {
        MuleClient client = new MuleClient(muleContext);
        client.dispatch("vm://in", TEST_MESSAGE, null);

        assertTrue(latch.await(RECEIVE_TIMEOUT, TimeUnit.MILLISECONDS));

        List<Message> receivedMessages = jabberClient.getReceivedMessages();
        assertEquals(1, receivedMessages.size());

        Message message = receivedMessages.get(0);
        assertXmppMessage(message);
    }

    @Test
    public void testReceiveAsync() throws Exception
    {
        startService(RECEIVE_SERVICE_NAME);

        Latch receiveLatch = new Latch();
        setupTestServiceComponent(receiveLatch);

        sendJabberMessageFromNewThread();
        assertTrue(receiveLatch.await(RECEIVE_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    private void setupTestServiceComponent(Latch receiveLatch) throws Exception
    {
        Object testComponent = getComponent(RECEIVE_SERVICE_NAME);
        assertTrue(testComponent instanceof FunctionalTestComponent);
        FunctionalTestComponent component = (FunctionalTestComponent) testComponent;

        XmppCallback callback = new XmppCallback(receiveLatch, expectedXmppMessageType());
        component.setEventCallback(callback);
    }

    protected Message.Type expectedXmppMessageType()
    {
        return Message.Type.normal;
    }

    protected void assertXmppMessage(Message message)
    {
        assertEquals(Message.Type.normal, message.getType());
        assertEquals(TEST_MESSAGE, message.getBody());
    }

    protected void sendJabberMessageFromNewThread()
    {
        JabberSender sender = new JabberSender(new Callback()
        {
            @Override
            public void doit() throws Exception
            {
                Thread.sleep(JABBER_SEND_THREAD_SLEEP_TIME);
                jabberClient.sendMessage(muleJabberUserId, TEST_MESSAGE);
            }
        });
        startSendThread(sender);
    }
}
