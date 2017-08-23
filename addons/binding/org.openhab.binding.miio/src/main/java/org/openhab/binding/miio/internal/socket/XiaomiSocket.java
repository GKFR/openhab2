package org.openhab.binding.miio.internal.socket;
/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * Takes care of the communication with MiHome devices.
 *
 *
 * @author Patrick Boos - Initial contribution
 * @author Dieter Schmidt - JavaDoc, refactored, reviewed
 * @author Marcel Verpaalen - reworked for miio binding
 *
 *
 */
public abstract class XiaomiSocket {

    private static final int BUFFER_LENGTH = 1024;
    private DatagramPacket datagramPacket = new DatagramPacket(new byte[BUFFER_LENGTH], BUFFER_LENGTH);

    private List<XiaomiSocketListener> listeners = new CopyOnWriteArrayList<>();

    private final Logger logger = LoggerFactory.getLogger(XiaomiSocket.class);

    private static ConcurrentHashMap<Integer, DatagramSocket> openSockets = new ConcurrentHashMap<Integer, DatagramSocket>();

    private int port;
    private DatagramSocket socket;
    private Thread socketReceiveThread;

    /**
     * Sets up an {@link XiaomiSocket} with the MiHome multicast address and a random port
     *
     */
    public XiaomiSocket() {
        this(0);
    }

    /**
     * Sets up an {@link XiaomiSocket} with the MiHome multicast address and a specific port
     *
     * @param port - the socket will be bound to this port
     */
    public XiaomiSocket(int port) {
        this.port = port;
    }

    public void intialize() {
        setupSocket();
        runReceiveThread();
    }

    protected void runReceiveThread() {
        socketReceiveThread = new ReceiverThread();
        socketReceiveThread.start();
        if (getSocket() != null) {
            getOpenSockets().put(getSocket().getLocalPort(), getSocket());
            logger.debug("There are {} open sockets: {}", getOpenSockets().size(), getOpenSockets());
        }
    }

    abstract DatagramSocket setupSocket();

    /**
     * Interrupts the {@link ReceiverThread} and closes the {@link XiaomiSocket}.
     */
    private void closeSocket() {
        synchronized (XiaomiSocket.class) {
            if (socketReceiveThread != null) {
                logger.debug("Interrupting Thread {}", socketReceiveThread);
                socketReceiveThread.interrupt();
            }
            if (getSocket() != null) {
                logger.debug("Closing socket {}", getSocket());
                openSockets.remove(getSocket().getLocalPort());
                getSocket().close();
                setSocket(null);
            }
        }
    }

    /**
     * Registers a {@link XiaomiSocketListener} to be called back, when data is received.
     * If no {@link XiaomiSocket} exists, when the method is called, it is being set up.
     *
     * @param listener - {@link XiaomiSocketListener} to be called back
     */
    public synchronized void registerListener(XiaomiSocketListener listener) {
        if (!getListeners().contains(listener)) {
            logger.trace("Adding socket listener {}", listener);
            getListeners().add(listener);
        }
        if (getSocket() == null) {
            intialize();
        }
    }

    /**
     * Unregisters a {@link XiaomiSocketListener}. If there are no listeners left,
     * the {@link XiaomiSocket} is being closed.
     *
     * @param listener - {@link XiaomiSocketListener} to be unregistered
     */
    public synchronized void unregisterListener(XiaomiSocketListener listener) {
        getListeners().remove(listener);

        if (getListeners().isEmpty()) {
            closeSocket();
        }
    }

    /**
     * Sends a message through the {@link XiaomiSocket} to a specific address and port
     *
     * @param message - Message to be sent
     * @param address - Address, to which the message shall be sent
     * @param port - - Port, through which the message shall be sent
     */
    public void sendMessage(byte[] sendData, InetAddress address, int port) {
        try {
            // byte[] sendData = message.getBytes("UTF-8");
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
            logger.debug("Sending message: {} bytes to {}:{}", sendData.length, address, port);
            getSocket().send(sendPacket);
        } catch (IOException e) {
            logger.error("Sending error", e);
        }
    }

    /**
     * @return - the port number of this {@link XiaomiSocket}
     */
    public int getPort() {
        return port;
    }

    /**
     * @return - a list of already open sockets
     */
    public static ConcurrentHashMap<Integer, DatagramSocket> getOpenSockets() {
        return openSockets;
    }

    protected DatagramSocket getSocket() {
        return socket;
    }

    protected void setSocket(DatagramSocket socket) {
        this.socket = socket;
    }

    protected List<XiaomiSocketListener> getListeners() {
        return listeners;
    }

    /**
     * The thread, which waits for data on the {@link XiaomiSocket} and handles it, when received
     *
     * @author Patrick Boos - Initial contribution
     * @author Dieter Schmidt - comments and synchronized block for callback instead of copy
     *
     */
    private class ReceiverThread extends Thread {
        @Override
        public void run() {
            logger.debug("Staring receiver thread for socket on port {}", getSocket().getLocalPort());
            receiveData(getSocket(), datagramPacket);
        }

        /**
         * This method is the main method of the {@link ReceiverThread} for the {@link XiaomiBridgeSocket}.
         * If the socket has data, it parses the data to a json object and calls all
         * {@link XiaomiSocketListener} and passes the data to them.
         *
         * @param socket - The multicast socket to listen to
         * @param dgram - The datagram to receive
         */
        private void receiveData(DatagramSocket socket, DatagramPacket dgram) {
            try {
                while (true) {
                    logger.trace("Thread {} waiting for data on port {}", this, socket.getLocalPort());
                    socket.receive(dgram);
                    InetAddress address = dgram.getAddress();
                    logger.trace("Received Datagram from {}:{} on Port {}", address.getHostAddress(), dgram.getPort(),
                            socket.getLocalPort());
                    byte[] response = Arrays.copyOfRange(dgram.getData(), dgram.getOffset(),
                            dgram.getOffset() + dgram.getLength());
                    notifyAll(getListeners(), response, address);
                    logger.trace("Data received and notified {} listeners", getListeners().size());
                }
            } catch (IOException e) {
                if (!isInterrupted()) {
                    logger.error("Error while receiving", e);
                } else {
                    logger.trace("Receiver thread was interrupted");
                }
            }
            logger.debug("Receiver thread ended");
        }

        /**
         * Notifies all {@link XiaomiSocketListener} on the parent {@link XiaomiSocket}. First checks for any matching
         * {@link XiaomiBridgeHandler}, before passing to any {@link XiaomiBridgeDiscoveryService}.
         *
         * @param listeners - a list of all {@link XiaomiSocketListener} to notify
         * @param response - the data message as {@link JsonObject}
         */

        //
        synchronized void notifyAll(List<XiaomiSocketListener> listeners, byte[] response, InetAddress address) {
            logger.trace("inform {} listeners. Data received {} bytes from {}", listeners.size(), response.length,
                    address);

            for (XiaomiSocketListener listener : listeners) {
                try {
                    listener.onDataReceived(response);
                } catch (Exception e) {
                    logger.debug("Could not inform listener {}, data {} from {}: ", listener, response.length, address,
                            e.getMessage(), e);

                }
            }
        }

    }
}