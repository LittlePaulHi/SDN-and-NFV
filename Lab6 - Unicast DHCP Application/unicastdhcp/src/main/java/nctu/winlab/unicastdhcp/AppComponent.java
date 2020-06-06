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
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import java.util.Queue;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.TpPort;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.Link;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
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
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final UnicastDhcpConfigListener cfgListener = new UnicastDhcpConfigListener();
    private final ConfigFactory<ApplicationId, UnicastDhcpConfig> configFactory = 
        new ConfigFactory<ApplicationId, UnicastDhcpConfig>(
            APP_SUBJECT_FACTORY, UnicastDhcpConfig.class, "UnicastDhcpConfig") {
                @Override
                public UnicastDhcpConfig createConfig() {
                    return new UnicastDhcpConfig();
                }
            };

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
    protected NetworkConfigRegistry cfgService;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    private ApplicationId appId;

    private PathFinder pathFinder;

    private String DhcpServerSwitchDeviceID;
    private String DhcpServerSwitchPort;

    private final int FlowPriority = 10;
    private final int FlowTimeout = 30;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        cfgService.addListener(cfgListener);
        cfgService.registerConfigFactory(configFactory);

        pathFinder = PathFinder.INSTANCE;

        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.removeListener(cfgListener);
        cfgService.unregisterConfigFactory(configFactory);
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    private class UnicastDhcpConfigListener implements NetworkConfigListener {
      @Override
      public void event(NetworkConfigEvent event) {
        if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                && event.configClass().equals(UnicastDhcpConfig.class)) {
            UnicastDhcpConfig config = cfgService.getConfig(appId, UnicastDhcpConfig.class);
            if (config != null) {
                log.info("DHCP server is at {}/{}", config.GetSwitchDevice(), config.GetPort());
                DhcpServerSwitchDeviceID = config.GetSwitchDevice();
                DhcpServerSwitchPort = config.GetPort();
            }
        }
      }
    }

    @Modified
    public void modified(ComponentContext context) {
        if (context != null) {
            requestIntercepts();
        }
        log.info("Reconfigured");
    }

    /**
    * Request packet in via packet service
    */
    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpDst(TpPort.tpPort(67));  // client -> server
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        selector.matchUdpDst(TpPort.tpPort(68));  // server -> client
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
    * Cancel request for packet in via packet service
    */
    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
    * Packet processor responsible for forwarding packets along their paths.
    */
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
            boolean isTODhcpServer = false;
            TopologyVertex srcVertex = new DefaultTopologyVertex(context.inPacket().receivedFrom().deviceId());
            TopologyVertex dstVertex = null;
            if (!srcVertex.deviceId().toString().equals(DhcpServerSwitchDeviceID)) {
                isTODhcpServer = true;
                dstVertex = new DefaultTopologyVertex(DeviceId.deviceId(DhcpServerSwitchDeviceID));
            }

            TopologyGraph graph = topologyService.getGraph(topologyService.currentTopology());
            for (TopologyVertex vertex : graph.getVertexes()) {
                if (dstVertex != null) {
                    break;
                }

                for (Host host : hostService.getConnectedHosts(vertex.deviceId())) {
                    if (host.mac().equals(inPacket.getDestinationMAC())) {
                        dstVertex = vertex;
                        break;
                    }
                }
            }

            if (dstVertex == null) {
                // log.info("Can't find vertex of destination");
                return;
            }

            PortNumber portNumber = null;
            if (isTODhcpServer) {
                portNumber = PortNumber.fromString(DhcpServerSwitchPort);
            }
            else {
                for(Host host : hostService.getHostsByMac(inPacket.getDestinationMAC())) {
                    portNumber = host.location().port();
                    break;
                } 
            }

            ComputePath(context, srcVertex, dstVertex, portNumber, isTODhcpServer);

            packetOut(context, PortNumber.TABLE);
        }

    }

    /**
    * Compute the path between requester and DHCP server
    */
    private void ComputePath(PacketContext context, TopologyVertex srcVertex, TopologyVertex dstVertex, PortNumber dstPortNumber, boolean isToDhcpServer) {
        Queue<DeviceId> deviceIds = pathFinder.Search(topologyService.getGraph(topologyService.currentTopology()),
                                                    srcVertex,
                                                    dstVertex);

        if (deviceIds == null) {
            log.info("Can't find the path (from {} to {})", srcVertex.deviceId().toString(), dstVertex.deviceId().toString());
            // return;
        }

        log.info("Start to install path from {} to {} ", srcVertex.deviceId().toString(), dstVertex.deviceId().toString());
        DeviceId currentDeviceId, previousDeviceId = null;
        PortNumber portNumber = null;
        // Traverse all the vertex from dst to src
        while (!deviceIds.isEmpty()) {
            currentDeviceId = deviceIds.poll();
            if (currentDeviceId.equals(dstVertex.deviceId())) {
                portNumber = dstPortNumber;
            }
            else {
                for (Link link : linkService.getDeviceEgressLinks(currentDeviceId)) {
                    if (link.dst().deviceId().equals(previousDeviceId)) {
                        portNumber = link.src().port();
                        break;
                    }
                }
            }

            installRule(context, currentDeviceId, portNumber, isToDhcpServer);
            previousDeviceId = currentDeviceId;
            portNumber = null;
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
    private void installRule(PacketContext context, DeviceId deviceId, PortNumber portNumber, boolean isToDhcpServer) {
        // Default activate proxyarp
        if (context.inPacket().parsed().getEtherType() == Ethernet.TYPE_ARP) {
            return;
        }

        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP);

        // Server and client use UDP port 67 and 68 respectively
        if (isToDhcpServer) {
            selectorBuilder.matchEthSrc(context.inPacket().parsed().getSourceMAC()).matchUdpDst(TpPort.tpPort(67));
        }
        else {  // to client
            selectorBuilder.matchEthSrc(context.inPacket().parsed().getSourceMAC()).matchUdpDst(TpPort.tpPort(68));
        }

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
    }

}
