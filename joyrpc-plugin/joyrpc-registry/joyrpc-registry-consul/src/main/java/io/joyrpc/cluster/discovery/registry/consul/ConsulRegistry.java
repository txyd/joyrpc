package io.joyrpc.cluster.discovery.registry.consul;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
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
 * #L%
 */

import com.ecwid.consul.transport.TransportException;
import com.ecwid.consul.v1.*;
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.agent.model.Self;
import com.ecwid.consul.v1.health.HealthServicesRequest;
import com.ecwid.consul.v1.health.model.HealthService;
import com.ecwid.consul.v1.health.model.HealthService.Service;
import com.ecwid.consul.v1.kv.model.GetValue;
import io.joyrpc.cluster.Region;
import io.joyrpc.cluster.Shard;
import io.joyrpc.cluster.discovery.backup.Backup;
import io.joyrpc.cluster.discovery.registry.AbstractRegistry;
import io.joyrpc.cluster.discovery.registry.URLKey;
import io.joyrpc.cluster.event.ClusterEvent;
import io.joyrpc.cluster.event.ClusterEvent.ShardEvent;
import io.joyrpc.cluster.event.ConfigEvent;
import io.joyrpc.codec.serialization.TypeReference;
import io.joyrpc.constants.Constants;
import io.joyrpc.constants.Version;
import io.joyrpc.context.Environment;
import io.joyrpc.context.GlobalContext;
import io.joyrpc.event.Publisher;
import io.joyrpc.event.UpdateEvent.UpdateType;
import io.joyrpc.extension.MapParametric;
import io.joyrpc.extension.Parametric;
import io.joyrpc.extension.URL;
import io.joyrpc.util.Futures;
import io.joyrpc.util.SystemClock;
import io.joyrpc.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static io.joyrpc.Plugin.ENVIRONMENT;
import static io.joyrpc.Plugin.JSON;
import static io.joyrpc.constants.Constants.*;
import static io.joyrpc.util.StringUtils.*;
import static io.joyrpc.util.Timer.timer;

/**
 * Consul注册中心
 */
public class ConsulRegistry extends AbstractRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ConsulRegistry.class);

    /**
     * 服务ID函数
     */
    protected static final Predicate<String> SERVICE_LOSS = s -> s != null && s.contains("does not have associated TTL");
    public static final String CONSUL_TTL = "consul.ttl";
    public static final String CONSUL_LEASE_INTERVAL = "consul.leaseInterval";
    public static final String CONSUL_BOOKING_INTERVAL = "consul.bookingInterval";

    protected List<String> addresses;

    protected int ttl;
    protected int leaseInterval;
    protected int bookingInterval;

    public ConsulRegistry(String name, URL url, Backup backup) {
        super(name, url, backup);
        String address = url.getString(Constants.ADDRESS_OPTION);
        this.addresses = !address.isEmpty() ? Arrays.asList(split(address, SEMICOLON_COMMA_WHITESPACE)) : Collections.emptyList();
        this.ttl = Math.max(url.getPositive(CONSUL_TTL, 30000), 30000);
        this.leaseInterval = url.getPositive(CONSUL_LEASE_INTERVAL, Math.min(ttl / 4, 10000));
        this.bookingInterval = url.getPositive(CONSUL_BOOKING_INTERVAL, 5000);
    }

    @Override
    protected RegistryController<? extends AbstractRegistry> create() {
        return new ConsulRegistryController(this);
    }

    /**
     * 随机获取地址
     *
     * @return
     */
    protected String randomAddress() {
        int size = addresses.size();
        switch (size) {
            case 0:
                return null;
            case 1:
                return addresses.get(0);
            default:
                return addresses.get(ThreadLocalRandom.current().nextInt(size));
        }
    }

    @Override
    protected Registion createRegistion(final URL url, final String key) {
        return new ConsulRegistion(url, key);
    }

    /**
     * Consul控制器
     */
    protected static class ConsulRegistryController extends RegistryController<ConsulRegistry> {
        /**
         * Consul可贺的
         */
        protected ConsulClient client;
        /**
         * 编码后的应用名称
         */
        protected String appPath;

        public ConsulRegistryController(ConsulRegistry registry) {
            super(registry);
            String appName = GlobalContext.getString(KEY_APPNAME);
            if (appName == null || appName.isEmpty()) {
                appPath = "";
            } else {
                try {
                    appPath = "/" + URLEncoder.encode(appName, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    appPath = "";
                }
            }
        }

        @Override
        protected ClusterBooking createClusterBooking(final URLKey key) {
            return new ConsulClusterBooking(key, this::dirty, getPublisher(key.getKey()), key.getUrl().getPath());
        }

        @Override
        protected ConfigBooking createConfigBooking(final URLKey key) {
            URL url = key.getUrl();
            String path = url.getPath() + "/" + url.getString(ROLE_OPTION) + appPath;
            return new ConsulConfigBooking(key, this::dirty, getPublisher(key.getKey()), path);
        }

        @Override
        protected CompletableFuture<Void> doConnect() {
            URL url = URL.valueOf(registry.randomAddress(), "http", 8500, null);
            //TODO 默认超时时间过长，DEFAULT_READ_TIMEOUT为10分钟
            ConsulRawClient.Builder builder = ConsulRawClient.Builder.builder();
            this.client = new ConsulClient(builder.setHost(url.getHost()).setPort(url.getPort()).build());
            return Futures.call(future -> {
                Response<Self> response = client.getAgentSelf();
                Self self = response.getValue();
                String dataCenter = self.getConfig().getDatacenter();
                //设置数据中心
                if (!isEmpty(dataCenter)) {
                    String[] parts = split(dataCenter, ':');
                    String region = null;
                    if (parts.length >= 2) {
                        //region:dataCenter
                        region = parts[0];
                        dataCenter = parts[1];
                    }
                    Environment environment = ENVIRONMENT.get();
                    if (!isEmpty(region) && isEmpty(GlobalContext.getString(REGION))) {
                        GlobalContext.put(REGION, region);
                        environment.put(REGION, region);
                    }
                    if (!isEmpty(dataCenter) && isEmpty(GlobalContext.getString(DATA_CENTER))) {
                        GlobalContext.put(DATA_CENTER, dataCenter);
                        environment.put(DATA_CENTER, dataCenter);
                    }
                }
                future.complete(null);
            });
        }

        @Override
        protected CompletableFuture<Void> doRegister(final Registion registion) {
            URL url = registion.getUrl();
            if (Constants.SIDE_CONSUMER.equals(url.getString(Constants.ROLE_OPTION.getName()))) {
                //消费者不注册
                return CompletableFuture.completedFuture(null);
            }
            ConsulRegistion cr = (ConsulRegistion) registion;
            cr.transportErrors.set(0);
            String serviceName = url.getPath();
            String ip = url.getHost();
            int port = url.getPort();
            //注册
            NewService service = new NewService();
            service.setId(cr.uuid);
            service.setMeta(getMeta(url));
            service.setAddress(ip);
            service.setName(serviceName);
            service.setPort(port);
            service.setTags(Arrays.asList(url.getString(ALIAS_OPTION)));
            //上报状态
            NewService.Check check = new NewService.Check();
            check.setDeregisterCriticalServiceAfter("10000ms");
            check.setTtl(registry.ttl + "ms");
            check.setStatus("passing");
            service.setCheck(check);
            return Futures.call(future -> {
                client.agentServiceRegister(service);
                cr.expireTime = SystemClock.now() + registry.ttl;
                long time = SystemClock.now() + registry.leaseInterval + ThreadLocalRandom.current().nextInt(2000);
                addLeaseTimer(registion, time);
                future.complete(null);
            });
        }

        @Override
        protected CompletableFuture<Void> doDeregister(final Registion registion) {
            return Futures.call(future -> {
                client.agentServiceDeregister(((ConsulRegistion) registion).uuid);
                future.complete(null);
            });
        }

        @Override
        protected CompletableFuture<Void> doSubscribe(final ClusterBooking booking) {
            return Futures.call(future -> {
                ConsulClusterBooking ccb = (ConsulClusterBooking) booking;
                doUpdate(ccb);
                addClusterTimer(ccb);
                future.complete(null);
            });
        }

        @Override
        protected CompletableFuture<Void> doSubscribe(final ConfigBooking booking) {
            return Futures.call(future -> {
                ConsulConfigBooking ccb = (ConsulConfigBooking) booking;
                doUpdate(ccb);
                addConfigTimer(ccb);
                future.complete(null);
            });
        }

        /**
         * 添加续约定时器
         *
         * @param registion  注册
         * @param expireTime 过期时间
         */
        protected void addLeaseTimer(final Registion registion, final long expireTime) {
            timer().add(new Timer.DelegateTask("Lease-" + registion.getKey(), expireTime, () -> addLeaseTask(registion)));
        }

        /**
         * 添加续约任务
         *
         * @param registion 注册
         */
        protected void addLeaseTask(final Registion registion) {
            if (!isOpen() || !connected.get() || !registers.containsKey(registion.getKey())) {
                return;
            }

            //添加任务
            //TODO 要不要用独立的线程池来管理
            addNewTask(new Task("Lease-" + registion.getKey(), null, new CompletableFuture<>(), () -> {
                if (isOpen() && connected.get() && registers.containsKey(registion.getKey())) {
                    doLease((ConsulRegistion) registion);
                }
                return CompletableFuture.completedFuture(null);
            }, 0, 0, 0, null));
        }

        /**
         * 添加集群订阅定时器
         *
         * @param booking 订阅
         */
        protected void addClusterTimer(final ConsulClusterBooking booking) {
            //加上随机时间
            long time = SystemClock.now() + registry.bookingInterval + ThreadLocalRandom.current().nextInt(2000);
            timer().add(new Timer.DelegateTask("Cluster-" + booking.getKey(), time, () -> addClusterTask(booking)));
        }

        /**
         * 添加集群订阅任务
         *
         * @param booking 订阅
         */
        protected void addClusterTask(final ConsulClusterBooking booking) {
            if (!isOpen() || !connected.get() || !clusters.containsKey(booking.getKey())) {
                return;
            }
            //添加任务
            addNewTask(new Task("Cluster-" + booking.getKey(), null, new CompletableFuture<>(), () -> {
                if (isOpen() && connected.get() && clusters.containsKey(booking.getKey())) {
                    try {
                        doUpdate(booking);
                    } finally {
                        //再次添加集群订阅定时器
                        addClusterTimer(booking);
                    }
                }
                return CompletableFuture.completedFuture(null);
            }, 0, 0, 0, null));
        }

        /**
         * 续约操作
         *
         * @param registion
         */
        protected void doLease(ConsulRegistion registion) {
            long time = 0;
            try {
                String serviceId = "service:" + registion.uuid;
                client.agentCheckPass(serviceId);
                registion.expireTime = SystemClock.now() + registry.ttl;
                registion.transportErrors.set(0);
            } catch (TransportException e) {
                //网络异常
                if (registion.transportErrors.incrementAndGet() == 3) {
                    time = -1;
                    //重连
                    logger.error(String.format("Transport error occurs more than 3 times, caused by %s. reconnect....", e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                    //先关闭连接，再重连
                    doDisconnect().whenComplete((v, t) -> {
                        if (isOpen()) {
                            reconnect(new CompletableFuture<>(), 0, registry.maxConnectRetryTimes);
                        }
                    });
                } else {
                    //网络异常加快检查
                    time = SystemClock.now() + 2000;
                }
            } catch (OperationException e) {
                if (SERVICE_LOSS.test(e.getStatusContent())) {
                    //服务注册信息丢失，可能服务端重启了
                    time = -1;
                    doRegister(registion);
                }
            } finally {
                if (time == 0) {
                    time = SystemClock.now() + registry.leaseInterval + ThreadLocalRandom.current().nextInt(2000);
                }
                if (time > 0) {
                    //再次添加续约任务
                    addLeaseTimer(registion, time);
                }
            }
        }

        /**
         * 更新集群
         *
         * @param booking 订阅
         */
        protected void doUpdate(final ConsulClusterBooking booking) {
            Response<List<HealthService>> response = client.getHealthServices(booking.getPath(),
                    HealthServicesRequest.newBuilder().setPassing(true)
                            .setTag(booking.getUrl().getString(ALIAS_OPTION))
                            .build());
            if (booking.getVersion() < 0 || response.getConsulIndex() > booking.getVersion()) {
                String defProtocol = GlobalContext.getString(Constants.PROTOCOL_KEY);
                List<HealthService> healthServices = response.getValue() == null ? new ArrayList<>(0) : response.getValue();
                List<ShardEvent> shards = new ArrayList<>(healthServices.size());
                healthServices.forEach(healthService -> {
                    Service service = healthService.getService();
                    Map<String, String> meta = service.getMeta();
                    String protocol = meta == null ? null : meta.remove(Constants.PROTOCOL_KEY);
                    protocol = protocol == null || protocol.isEmpty() ? defProtocol : protocol;
                    URL url = new URL(protocol, service.getAddress(), service.getPort(), service.getService(), meta);
                    shards.add(new ShardEvent(new Shard.DefaultShard(url), ClusterEvent.ShardEventType.UPDATE));
                });
                booking.handle(new ClusterEvent(registry, null, UpdateType.FULL, response.getConsulIndex(), shards));
            }
        }

        /**
         * 添加配置订阅定时器
         *
         * @param booking 订阅
         */
        protected void addConfigTimer(final ConsulConfigBooking booking) {
            //加上随机时间
            long time = SystemClock.now() + registry.bookingInterval + ThreadLocalRandom.current().nextInt(2000);
            timer().add(new Timer.DelegateTask("Cluster-" + booking.getKey(), time, () -> addConfigTask(booking)));
        }

        /**
         * 添加配置订阅任务
         *
         * @param booking 订阅
         */
        protected void addConfigTask(final ConsulConfigBooking booking) {
            if (!isOpen() || !connected.get() || !configs.containsKey(booking.getKey())) {
                return;
            }
            //添加任务
            addNewTask(new Task("Cluster-" + booking.getKey(), null, new CompletableFuture<>(), () -> {
                if (isOpen() && connected.get() && configs.containsKey(booking.getKey())) {
                    try {
                        doUpdate(booking);
                    } finally {
                        //再次添加配置订阅定时器
                        addConfigTimer(booking);
                    }
                }
                return CompletableFuture.completedFuture(null);
            }, 0, 0, 0, null));
        }

        /**
         * 更新配置
         *
         * @param booking 配置订阅
         */
        protected void doUpdate(final ConsulConfigBooking booking) {
            if (!isOpen() || !connected.get() || !configs.containsKey(booking.getKey())) {
                return;
            }
            QueryParams params = QueryParams.Builder.builder().setIndex(booking.getVersion()).setWaitTime(0).build();
            Response<GetValue> response = client.getKVValue(booking.getPath(), params);
            GetValue getValue = response.getValue();
            if (getValue != null && getValue.getModifyIndex() > booking.getVersion()) {
                Map<String, String> map = JSON.get().parseObject(getValue.getDecodedValue(StandardCharsets.UTF_8), new TypeReference<Map<String, String>>() {
                });
                booking.handle(new ConfigEvent(registry, null, getValue.getModifyIndex(), map));
            } else if (booking.getVersion() < 0) {
                booking.handle(new ConfigEvent(registry, null, 0, new HashMap<>()));
            }
        }

        /**
         * 获取注册的元数据
         *
         * @param url
         * @return
         */
        protected Map<String, String> getMeta(URL url) {
            Map<String, String> result = new HashMap<>(30);
            Parametric context = new MapParametric(GlobalContext.getContext());
            result.put(KEY_APPAPTH, context.getString(KEY_APPAPTH));
            result.put(KEY_APPID, context.getString(KEY_APPID));
            result.put(KEY_APPNAME, context.getString(KEY_APPNAME));
            result.put(KEY_APPINSID, context.getString(KEY_APPINSID));
            result.put(JAVA_VERSION_KEY, context.getString(KEY_JAVA_VERSION));
            result.put(VERSION_KEY, context.getString(VERSION_KEY));
            result.put(Region.REGION, registry.getRegion());
            result.put(Region.DATA_CENTER, registry.getDataCenter());
            result.put(BUILD_VERSION_KEY, String.valueOf(Version.BUILD_VERSION));
            if (url.getBoolean(SSL_ENABLE)) {
                result.put(SSL_ENABLE.getName(), "true");
            }
            result.put(SERIALIZATION_OPTION.getName(), url.getString(SERIALIZATION_OPTION));
            result.put(WEIGHT_OPTION.getName(), String.valueOf(url.getInteger(WEIGHT_OPTION)));
            result.put(TIMESTAMP_KEY, String.valueOf(SystemClock.now()));
            result.put(PROTOCOL_KEY, url.getProtocol());
            result.put(ALIAS_OPTION.getName(), url.getString(ALIAS_OPTION));
            return result;
        }

    }

    /**
     * 注册信息
     */
    protected static class ConsulRegistion extends Registion {
        /**
         * 过期时间
         */
        protected long expireTime;
        /**
         * 唯一ID
         */
        protected String uuid;
        /**
         * 连续网络异常
         */
        protected AtomicInteger transportErrors = new AtomicInteger();

        public ConsulRegistion(URL url, String key) {
            super(url, key);
            uuid = UUID.randomUUID().toString();
        }

        /**
         * 是否过期了
         *
         * @return 过期标识
         */
        public boolean isExpire() {
            return expireTime <= SystemClock.now();
        }
    }

    /**
     * 配置订阅
     */
    protected static class ConsulConfigBooking extends ConfigBooking {
        public ConsulConfigBooking(URLKey key, Runnable dirty, Publisher<ConfigEvent> publisher, String path) {
            super(key, dirty, publisher, path);
        }
    }

    /**
     * 集群订阅
     */
    protected static class ConsulClusterBooking extends ClusterBooking {

        public ConsulClusterBooking(URLKey key, Runnable dirty, Publisher<ClusterEvent> publisher, String path) {
            super(key, dirty, publisher, path);
        }

    }


}