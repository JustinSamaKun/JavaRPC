package org.kipdev.rpc.implementations.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.Getter;
import org.kipdev.rpc.Exchange;
import org.kipdev.rpc.RPCController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class RabbitMessageController extends RPCController {

    public static RabbitMessageController INSTANCE = new RabbitMessageController();

    private final Map<String, Channel> channelMap = new ConcurrentHashMap<>();

    private Connection connection = null;

    private RabbitCredentials credentials;

    /**
     * Must be called in order to interface with RabbitMQ
     * @param credentials Credentials of Rabbit
     */
    @SuppressWarnings("unused")
    public void initialize(RabbitCredentials credentials, String basePackage) {
        RPCController.INSTANCE = INSTANCE = this;

        this.credentials = credentials;

        registerPackage(basePackage);

        try {
            connection = getNewConnectionFactory().newConnection();
        } catch (Exception e) {
            throw new RuntimeException("Error connecting to RabbitMQ", e);
        }
    }

    private ConnectionFactory getNewConnectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(credentials.getHost());
        factory.setUsername(credentials.getUsername());
        factory.setPassword(credentials.getPassword());
        factory.setVirtualHost(credentials.getVirtualHost());
        return factory;
    }

    public void initializeExchange(String channelName, Exchange receiver) {
        try {
            Channel channel = getChannel();

            channel.exchangeDeclare(channelName, "fanout", false, false, false, null);
            String queueName = channel.queueDeclare().getQueue();
            channel.queueBind(queueName, channelName, "");

            channel.basicConsume(queueName, true, new RabbitConsumer(channel, channelName, this::handleMessage));
            channelMap.put(channelName, channel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void dismissExchange(String channelName) {
        Channel remove = channelMap.remove(channelName);
        if (remove != null) {
            try {
                remove.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void publish(String exchange, byte[] data) {
        try {
            Channel channel = getChannel();
            channel.basicPublish(exchange, "", new AMQP.BasicProperties.Builder().deliveryMode(1).build(), data);
            channel.close();
        } catch (Exception e) {
            throw new RuntimeException("Could not send Rabbit message", e);
        }
    }

    private Channel getChannel() {
        if (!connection.isOpen()) {
            reconnect();
        }
        try {
            return connection.createChannel();
        } catch (IOException e) {
            throw new RuntimeException("Error creating RabbitMQ channel....", e);
        }
    }

    public void reconnect() {
        try {
            System.err.println("RabbitMQ wasn't connected... Opening new connection.");
            connection = getNewConnectionFactory().newConnection();

            try {
                for (Channel channel : channelMap.values()) {
                    channel.close();
                }
            } catch (Throwable ignored) {
            }
            channelMap.clear();

            for (Map.Entry<String, Exchange> entry : getExchanges().entrySet()) {
                initializeExchange(entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
