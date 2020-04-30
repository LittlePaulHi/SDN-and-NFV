/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.pathapp;

import java.util.Iterator;
import java.util.Queue;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.IpAddress;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.Link;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.DefaultTopologyVertex;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.TopologyVertex;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skeletal ONOS application component
 */
@Component(immediate = true,
           service = {SomeInterface.class}/*,
           property = {
               "someProperty=Some Default String Value",
           }*/)
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /* Some configurable property */
    // private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    private ApplicationId appId;

    private PathFinder pathFinder;

    private final int FlowPriority = 10;
    private final int FlowTimeout = 30;

    @Activate
    protected void activate() {
        // cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.winlab.pathapp");

        pathFinder = PathFinder.INSTANCE;

        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        // Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            // someProperty = get(properties, "someProperty");
            requestIntercepts();
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        // log.info("Invoked");
    }

    /**
    * Request packet in via packet service
    */
    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        // selector.matchEthType(Ethernet.TYPE_IPV6);  // default disable ipv6
        // packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
    * Cancel request for packet in via packet service
    */
    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        // selector.matchEthType(Ethernet.TYPE_IPV6);
        // packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private class ReactivePacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            // Stop processing if the packet has been handled, since we can't do anymore to it
            if (context.isHandled()) {
                return;
            }

            Ethernet inPacket = context.inPacket().parsed();  // parse the packet-in
            // Bail if this is null or is a control packet
            if ((inPacket == null) || isControlPacket(inPacket)) {
                // log.info("ethPacket is null or is a control packet");
                return;
            }

            // Only deal with IPv4 packet
            if (inPacket.getEtherType() != Ethernet.TYPE_IPV4) {
                return;
            }

            // Do not process LLDP MAC address in any way
            if (inPacket.getDestinationMAC().isLldp()) {
                // log.info("ethPacket is LLDP packet");
                return;
            }

            // Find the device id of dst
            IPv4 ipv4Packet = (IPv4) inPacket.getPayload();
            TopologyVertex dstVertex = null;

            TopologyGraph graph = topologyService.getGraph(topologyService.currentTopology());
            for (TopologyVertex vertex : graph.getVertexes()) {
                for (Host host : hostService.getConnectedHosts(vertex.deviceId())) {
                    if (host.ipAddresses().contains(IpAddress.valueOf(IPv4.fromIPv4Address(ipv4Packet.getDestinationAddress())))) {
                        dstVertex = vertex;
                        break;
                    }
                }

                if (dstVertex != null) {
                    break;
                }
            }

            if (dstVertex == null) {
                // log.info("ERROR: Can't find vertex of dst!!");
                return;
            }

            log.info("Packet-in from device " + context.inPacket().receivedFrom().deviceId());
            Queue<DeviceId> deviceIds = pathFinder.Search(graph,
                                                new DefaultTopologyVertex(context.inPacket().receivedFrom().deviceId()),
                                                dstVertex);

            if (deviceIds == null) {
                log.info("Error: Can't find the path");
                // return;
            }

            log.info("Start to install path from " + inPacket.getDestinationMAC().toString() +
                    " to " + inPacket.getSourceMAC().toString());
            DeviceId currentDeviceId, previousDeviceId = null;
            PortNumber portNumber = null;
            // Traverse all the vertex from dst to src
            while (!deviceIds.isEmpty()) {
                currentDeviceId = deviceIds.poll();
                if (currentDeviceId.equals(dstVertex.deviceId())) {
                    Iterator<Host> iterator = hostService.getHostsByIp(IpAddress.valueOf(IPv4.fromIPv4Address(ipv4Packet.getDestinationAddress()))).iterator();
                    if (iterator.hasNext()) {
                        portNumber = iterator.next().location().port();
                    }
                    else {
                        log.info("Error: can't find the port number of dst vertex/switch");
                    }
                }
                else {
                    for (Link link : linkService.getDeviceEgressLinks(currentDeviceId)) {
                        if (link.dst().deviceId().equals(previousDeviceId)) {
                            portNumber = link.src().port();
                            break;
                        }
                    }
                }

                installRule(context, currentDeviceId, portNumber);
                previousDeviceId = currentDeviceId;
                portNumber = null;
            }
            // packetOut(context, portNumber);
        }
        
    }

    /**
    * Indicates whether this is a control packet, e.g. LLDP, BDDP
    */
    private boolean isControlPacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }

    /**
    * Sends a packet out the specified port
    */
    private void packetOut(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    /**
    * Install a flow rule to forward the packet to the specified port
    */
    private void installRule(PacketContext context, DeviceId deviceId, PortNumber portNumber) {
        Ethernet inPacket = context.inPacket().parsed();

        // Default activate proxyarp
        if (inPacket.getEtherType() == Ethernet.TYPE_ARP) {
            return;
        }

        IPv4 ipv4Packet = (IPv4) inPacket.getPayload();
        Ip4Prefix matchIp4SrcPrefix =
                Ip4Prefix.valueOf(ipv4Packet.getSourceAddress(), Ip4Prefix.MAX_MASK_LENGTH);
        Ip4Prefix matchIp4DstPrefix =
                Ip4Prefix.valueOf(ipv4Packet.getDestinationAddress(), Ip4Prefix.MAX_MASK_LENGTH);

        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(matchIp4SrcPrefix)
                .matchIPDst(matchIp4DstPrefix);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(portNumber).build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(FlowPriority)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(FlowTimeout).add();

        flowObjectiveService.forward(deviceId, forwardingObjective);

        log.info("Install flow rule on " + deviceId);
        // packetOut(context, portNumber);
    }

}
