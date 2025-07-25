/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.functions.utils.functioncache.FunctionCacheEntry.JAVA_INSTANCE_JAR_PROPERTY;
import static org.apache.pulsar.functions.worker.PulsarFunctionLocalRunTest.getPulsarApiExamplesJar;
import static org.apache.pulsar.functions.worker.PulsarFunctionLocalRunTest.getPulsarApiExamplesNar;
import static org.apache.pulsar.functions.worker.PulsarFunctionLocalRunTest.getPulsarIOBatchDataGeneratorNar;
import static org.apache.pulsar.functions.worker.PulsarFunctionLocalRunTest.getPulsarIODataGeneratorNar;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.assertTrue;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.ServiceConfigurationUtils;
import org.apache.pulsar.broker.authentication.AuthenticationProviderTls;
import org.apache.pulsar.broker.authorization.PulsarAuthorizationProvider;
import org.apache.pulsar.broker.loadbalance.impl.SimpleLoadManagerImpl;
import org.apache.pulsar.client.admin.BrokerStats;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.auth.AuthenticationTls;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.TopicType;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.functions.runtime.thread.ThreadRuntimeFactory;
import org.apache.pulsar.functions.runtime.thread.ThreadRuntimeFactoryConfig;
import org.apache.pulsar.functions.worker.FileServer;
import org.apache.pulsar.functions.worker.PulsarFunctionTestTemporaryDirectory;
import org.apache.pulsar.functions.worker.PulsarWorkerService;
import org.apache.pulsar.functions.worker.WorkerConfig;
import org.apache.pulsar.functions.worker.WorkerService;
import org.apache.pulsar.utils.ResourceUtils;
import org.apache.pulsar.zookeeper.LocalBookkeeperEnsemble;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public abstract class AbstractPulsarE2ETest {

    public static final Logger LOG = LoggerFactory.getLogger(AbstractPulsarE2ETest.class);

    protected static final String TLS_SERVER_CERT_FILE_PATH =
            ResourceUtils.getAbsolutePath("certificate-authority/server-keys/broker.cert.pem");
    protected static final String TLS_SERVER_KEY_FILE_PATH =
            ResourceUtils.getAbsolutePath("certificate-authority/server-keys/broker.key-pk8.pem");
    protected static final String TLS_CLIENT_CERT_FILE_PATH =
            ResourceUtils.getAbsolutePath("certificate-authority/client-keys/admin.cert.pem");
    protected static final String TLS_CLIENT_KEY_FILE_PATH =
            ResourceUtils.getAbsolutePath("certificate-authority/client-keys/admin.key-pk8.pem");
    protected static final String TLS_TRUST_CERT_FILE_PATH =
            ResourceUtils.getAbsolutePath("certificate-authority/certs/ca.cert.pem");
    protected final String tenant = "external-repl-prop";

    protected LocalBookkeeperEnsemble bkEnsemble;
    protected ServiceConfiguration config;
    protected WorkerConfig workerConfig;
    protected PulsarService pulsar;
    protected PulsarAdmin admin;
    protected PulsarClient pulsarClient;
    protected BrokerStats brokerStatsClient;
    protected PulsarWorkerService functionsWorkerService;
    protected String pulsarFunctionsNamespace = tenant + "/pulsar-function-admin";
    protected String primaryHost;
    protected String workerId;
    protected PulsarFunctionTestTemporaryDirectory tempDirectory;
    protected FileServer fileServer;

    @DataProvider(name = "validRoleName")
    public Object[][] validRoleName() {
        return new Object[][] { { Boolean.TRUE }, { Boolean.FALSE } };
    }

    @BeforeMethod(alwaysRun = true)
    public void setup(Method method) throws Exception {
        LOG.info("--- Setting up method {} ---", method.getName());

        // Start local bookkeeper ensemble
        bkEnsemble = new LocalBookkeeperEnsemble(3, 0, () -> 0);
        bkEnsemble.start();

        config = new ServiceConfiguration();
        config.setClusterName("use");
        Set<String> superUsers = Sets.newHashSet("superUser", "admin");
        config.setSuperUserRoles(superUsers);
        config.setWebServicePort(Optional.of(0));
        config.setWebServicePortTls(Optional.of(0));
        config.setMetadataStoreUrl("zk:127.0.0.1:" + bkEnsemble.getZookeeperPort());
        config.setBrokerShutdownTimeoutMs(0L);
        config.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        config.setBrokerServicePort(Optional.of(0));
        config.setBrokerServicePortTls(Optional.of(0));
        config.setLoadManagerClassName(SimpleLoadManagerImpl.class.getName());
        config.setTlsAllowInsecureConnection(true);
        config.setAdvertisedAddress("localhost");

        Set<String> providers = new HashSet<>();
        providers.add(AuthenticationProviderTls.class.getName());
        config.setAuthenticationEnabled(true);
        config.setAuthenticationProviders(providers);

        config.setAuthorizationEnabled(true);
        config.setAuthorizationProvider(PulsarAuthorizationProvider.class.getName());

        config.setTlsCertificateFilePath(TLS_SERVER_CERT_FILE_PATH);
        config.setTlsKeyFilePath(TLS_SERVER_KEY_FILE_PATH);
        config.setTlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);

        config.setBrokerClientAuthenticationPlugin(AuthenticationTls.class.getName());
        config.setBrokerClientAuthenticationParameters(
                "tlsCertFile:" + TLS_CLIENT_CERT_FILE_PATH + "," + "tlsKeyFile:" + TLS_CLIENT_KEY_FILE_PATH);
        config.setBrokerClientTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);
        config.setBrokerClientTlsEnabled(true);
        config.setAllowAutoTopicCreationType(TopicType.NON_PARTITIONED);

        System.setProperty(JAVA_INSTANCE_JAR_PROPERTY,
                FutureUtil.class.getProtectionDomain().getCodeSource().getLocation().getPath());

        functionsWorkerService = createPulsarFunctionWorker(config);

        // populate builtin connectors folder
        if (Arrays.asList(method.getAnnotation(Test.class).groups()).contains("builtin")) {
            File connectorsDir = new File(workerConfig.getConnectorsDirectory());

            File file = getPulsarIODataGeneratorNar();
            Files.copy(file.toPath(), new File(connectorsDir, file.getName()).toPath());

            file = getPulsarIOBatchDataGeneratorNar();
            Files.copy(file.toPath(), new File(connectorsDir, file.getName()).toPath());

            File functionsDir = new File(workerConfig.getFunctionsDirectory());

            file = getPulsarApiExamplesNar();
            Files.copy(file.toPath(), new File(functionsDir, file.getName()).toPath());
        }

        Optional<WorkerService> functionWorkerService = Optional.of(functionsWorkerService);
        pulsar = new PulsarService(config, workerConfig, functionWorkerService, (exitCode) -> {});
        pulsar.start();

        Map<String, String> authParams = new HashMap<>();
        authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
        authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);
        Authentication authTls = new AuthenticationTls();
        authTls.configure(authParams);

        admin = spy(
                PulsarAdmin.builder().serviceHttpUrl(pulsar.getWebServiceAddressTls())
                        .tlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH)
                        .allowTlsInsecureConnection(true).authentication(authTls).build());

        brokerStatsClient = admin.brokerStats();
        primaryHost = String.format("http://%s:%d", "localhost", pulsar.getListenPortHTTP().get());

        // update cluster metadata
        ClusterData clusterData = ClusterData.builder()
                .serviceUrl(pulsar.getWebServiceAddress())
                .serviceUrlTls(pulsar.getWebServiceAddressTls())
                .brokerServiceUrl(pulsar.getBrokerServiceUrl())
                .brokerServiceUrlTls(pulsar.getBrokerServiceUrlTls())
                .build();
        admin.clusters().updateCluster(config.getClusterName(), clusterData);

        ClientBuilder clientBuilder = PulsarClient.builder().serviceUrl(this.workerConfig.getPulsarServiceUrl());
        if (isNotBlank(workerConfig.getBrokerClientAuthenticationPlugin())
                && isNotBlank(workerConfig.getBrokerClientAuthenticationParameters())) {
            clientBuilder.enableTls(workerConfig.isUseTls());
            clientBuilder.allowTlsInsecureConnection(workerConfig.isTlsAllowInsecureConnection());
            clientBuilder.authentication(workerConfig.getBrokerClientAuthenticationPlugin(),
                    workerConfig.getBrokerClientAuthenticationParameters());
        }
        if (pulsarClient != null) {
            pulsarClient.close();
        }
        pulsarClient = clientBuilder.build();

        TenantInfo propAdmin = TenantInfo.builder()
                .adminRoles(Collections.singleton("superUser"))
                .allowedClusters(Collections.singleton("use"))
                .build();
        admin.tenants().updateTenant(tenant, propAdmin);

        assertTrue(getPulsarIODataGeneratorNar().exists(), "pulsar-io-data-generator.nar file "
                + getPulsarIODataGeneratorNar().getAbsolutePath() + " doesn't exist.");
        assertTrue(getPulsarIOBatchDataGeneratorNar().exists(), "pulsar-io-batch-data-generator.nar file "
                + getPulsarIOBatchDataGeneratorNar().getAbsolutePath() + " doesn't exist.");
        assertTrue(getPulsarApiExamplesJar().exists(), "pulsar-functions-api-examples.jar file "
                + getPulsarApiExamplesJar().getAbsolutePath() + " doesn't exist.");

        // setting up simple web server to test submitting function via URL
        fileServer = new FileServer();
        fileServer.serveFile("/pulsar-io-data-generator.nar", getPulsarIODataGeneratorNar());
        fileServer.serveFile("/pulsar-io-batch-data-generator.nar", getPulsarIOBatchDataGeneratorNar());
        fileServer.serveFile("/pulsar-functions-api-examples.jar", getPulsarApiExamplesJar());
        fileServer.serveFile("/pulsar-functions-api-examples.nar", getPulsarApiExamplesNar());
        fileServer.start();

        Awaitility.await().until(() -> functionsWorkerService.getLeaderService().isLeader());
    }

    @AfterMethod(alwaysRun = true)
    void shutdown() throws Exception {
        LOG.info("--- Shutting down ---");
        try {
            if (fileServer != null) {
                fileServer.stop();
                fileServer = null;
            }

            if (pulsarClient != null) {
                pulsarClient.close();
                pulsarClient = null;
            }

            if (admin != null) {
                admin.close();
                admin = null;
            }

            if (functionsWorkerService != null) {
                functionsWorkerService.stop();
                functionsWorkerService = null;
            }

            if (pulsar != null) {
                pulsar.close();
                pulsar = null;
            }

            if (bkEnsemble != null) {
                bkEnsemble.stop();
                bkEnsemble = null;
            }
        } finally {
            if (tempDirectory != null) {
                tempDirectory.delete();
            }
        }
    }

    private PulsarWorkerService createPulsarFunctionWorker(ServiceConfiguration config) throws IOException {

        System.setProperty(JAVA_INSTANCE_JAR_PROPERTY,
                FutureUtil.class.getProtectionDomain().getCodeSource().getLocation().getPath());

        workerConfig = new WorkerConfig();
        tempDirectory = PulsarFunctionTestTemporaryDirectory.create(getClass().getSimpleName());
        tempDirectory.useTemporaryDirectoriesForWorkerConfig(workerConfig);
        workerConfig.setPulsarFunctionsNamespace(pulsarFunctionsNamespace);
        workerConfig.setSchedulerClassName(
                org.apache.pulsar.functions.worker.scheduler.RoundRobinScheduler.class.getName());
        workerConfig.setFunctionRuntimeFactoryClassName(ThreadRuntimeFactory.class.getName());
        workerConfig.setFunctionRuntimeFactoryConfigs(ObjectMapperFactory.getMapper().getObjectMapper().convertValue(
                new ThreadRuntimeFactoryConfig().setThreadGroupName("use"), Map.class)); // worker talks to local broker
        workerConfig.setFailureCheckFreqMs(100);
        workerConfig.setNumFunctionPackageReplicas(1);
        workerConfig.setClusterCoordinationTopicName("coordinate");
        workerConfig.setFunctionAssignmentTopicName("assignment");
        workerConfig.setFunctionMetadataTopicName("metadata");
        workerConfig.setInstanceLivenessCheckFreqMs(100);
        workerConfig.setWorkerPort(0);
        workerConfig.setPulsarFunctionsCluster(config.getClusterName());
        String hostname = ServiceConfigurationUtils.getDefaultOrConfiguredAddress(config.getAdvertisedAddress());
        this.workerId = "c-" + config.getClusterName() + "-fw-" + hostname + "-" + workerConfig.getWorkerPort();
        workerConfig.setWorkerHostname(hostname);
        workerConfig.setWorkerId(workerId);

        workerConfig.setBrokerClientAuthenticationPlugin(AuthenticationTls.class.getName());
        workerConfig.setBrokerClientAuthenticationParameters(
                String.format("tlsCertFile:%s,tlsKeyFile:%s", TLS_CLIENT_CERT_FILE_PATH, TLS_CLIENT_KEY_FILE_PATH));
        workerConfig.setUseTls(true);
        workerConfig.setTlsAllowInsecureConnection(true);
        workerConfig.setTlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);

        workerConfig.setAuthenticationEnabled(true);
        workerConfig.setAuthorizationEnabled(true);

        List<String> urlPatterns =
                List.of(getPulsarApiExamplesJar().getParentFile().toURI() + ".*", "http://127\\.0\\.0\\.1:.*");
        workerConfig.setAdditionalEnabledConnectorUrlPatterns(urlPatterns);
        workerConfig.setAdditionalEnabledFunctionsUrlPatterns(urlPatterns);

        PulsarWorkerService workerService = new PulsarWorkerService();
        return workerService;
    }
}
