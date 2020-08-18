/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.kitodo.production.interfaces.activemq;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.config.ConfigCore;
import org.kitodo.config.enums.ParameterCore;

/**
 * The class ActiveMQDirector is the head of all Active MQ processors. It
 * implements the ServletContextListener interface and is called automatically
 * upon server startup. Its job is to connect to the Active MQ server and
 * register the listeners configured.
 *
 * <p>
 * The Active MQ services are intended to be run in case that
 * {@code activeMQ.hostURL} is configured in the kitodo_config.properties file.
 * To disable the service, the entry there should be commented out.
 *
 * <p>
 * The class ActiveMQDirector also provides a basic ExceptionListener
 * implementation as required for the connection.
 */
@WebListener
public class ActiveMQDirector implements ServletContextListener {
    private static final Logger logger = LogManager.getLogger(ActiveMQDirector.class);

    // When implementing new services, add them to this list
    private static Collection<? extends ActiveMQProcessor> services;

    static {
        services = Arrays.asList(new FinalizeStepProcessor());
    }

    private static Connection connection = null;
    private static Session session = null;
    private static MessageProducer resultsTopic;

    /**
     * The method is called by the web container on startup
     * and is used to start up the active MQ connection. All processors from
     * {@link #services} are registered.
     */
    @Override
    public void contextInitialized(ServletContextEvent initialisation) {
        Optional<String> activeMQHost = ConfigCore.getOptionalString(ParameterCore.ACTIVE_MQ_HOST_URL);
        if (activeMQHost.isPresent()) {
            session = connectToServer(activeMQHost.get());
            if (Objects.nonNull(session)) {
                registerListeners(services);
                Optional<String> activeMQResultsTopic = ConfigCore
                        .getOptionalString(ParameterCore.ACTIVE_MQ_RESULTS_TOPIC);
                activeMQResultsTopic.ifPresent(topic -> resultsTopic = setUpReportChannel(topic));
            }
        }
    }

    /**
     * Sets up a connection to an active MQ server. The connection object is
     * global because it is needed later to shut down the connection.
     *
     * @param server
     *            should be “tcp://{host}:{port}” or “vm://localhost” in case
     *            that the server is run inside the same virtual machine
     * @return the session object or “null” upon error
     */
    private Session connectToServer(String server) {
        try {
            connection = new ActiveMQConnectionFactory(server).createConnection();
            connection.start();
            connection.setExceptionListener(exception -> logger.error(exception));
            return connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        } catch (JMSException | RuntimeException e) {
            logger.fatal("Error connecting to ActiveMQ server, giving up.", e);
        }
        return null;
    }

    /**
     * This method registers the listeners with the active MQ server.
     *
     * <p>
     * If a queue name was configured for a service, a MessageConsumer is set up
     * to listen on that queue and, in case of incoming messages, make the
     * service process the message. The message checker is saved inside the
     * service to be able to shut it down later.
     */
    private void registerListeners(Collection<? extends ActiveMQProcessor> processors) {
        for (ActiveMQProcessor processor : processors) {
            if (Objects.nonNull(processor.getQueueName())) {
                MessageConsumer messageConsumer;
                try {
                    Destination queue = session.createQueue(processor.getQueueName());
                    messageConsumer = session.createConsumer(queue);
                    messageConsumer.setMessageListener(processor);
                    processor.setMessageConsumer(messageConsumer);
                } catch (JMSException | RuntimeException e) {
                    logger.fatal("Error setting up monitoring for \"" + processor.getQueueName() + "\": Giving up.", e);
                }
            }
        }
    }

    /**
     * This sets up a connection to the topic the results shall be written to.
     * The delivery mode is set so “persistent” to allow consumers not online
     * with the server in the moment of message submission to read the messages
     * later. The log messages are set to be kept on the server for 7 days. This
     * value can be overridden from the kitodo_config.properties parameter
     * “activeMQ.results.timeToLive”. The time to live must be specified in
     * milliseconds; 0 disables the oblivion. (See also:
     * http://docs.oracle.com/javaee/6/api/javax/jms/MessageProducer.html#setTimeToLive%28long%29
     * )
     *
     * @param topic
     *            name of the active MQ topic
     * @return a MessageProducer object ready for writing or “null” on error
     */
    private MessageProducer setUpReportChannel(String topic) {
        MessageProducer reportChannel;
        try {
            Destination channel = session.createTopic(topic);
            reportChannel = session.createProducer(channel);
            reportChannel.setDeliveryMode(DeliveryMode.PERSISTENT);
            reportChannel.setTimeToLive(ConfigCore.getLongParameterOrDefaultValue(ParameterCore.ACTIVE_MQ_RESULTS_TTL));
            return reportChannel;
        } catch (JMSException | RuntimeException e) {
            logger.fatal("Error setting up report channel \"" + topic + "\": Giving up.", e);
        }
        return null;
    }

    /**
     * Any class that wants to create new Active MQ Messages needs read access
     * to the session, since Active MQ messages don’t have a constructor.
     *
     * @return the session object
     */
    public static Session getSession() {
        return session;
    }

    /**
     * Instances of WebServiceResult can be sent by calling their send() method.
     * Therefore, they need read access on their topic.
     *
     * @return the resultsTopic object
     */
    public static MessageProducer getResultsTopic() {
        return resultsTopic;
    }

    /**
     * The method contextDestroyed is called by the web container on shutdown.
     * It shuts down all listeners, the session and last, the connection.
     */
    @Override
    public void contextDestroyed(ServletContextEvent destruction) {
        // Shut down all message consumers on any queues
        for (ActiveMQProcessor service : services) {
            MessageConsumer messageConsumer = service.getMessageConsumer();
            if (Objects.nonNull(messageConsumer)) {
                try {
                    messageConsumer.close();
                } catch (JMSException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }

        // quit session
        try {
            if (Objects.nonNull(session)) {
                session.close();
            }
        } catch (JMSException e) {
            logger.error(e.getMessage(), e);
        }

        // shut down connection
        try {
            if (Objects.nonNull(connection)) {
                connection.close();
            }
        } catch (JMSException e) {
            logger.error(e.getMessage(), e);
        }
    }
}
