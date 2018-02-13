/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.configurations.ribbon;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.reactive.ExecutionListener;
import com.netflix.loadbalancer.reactive.LoadBalancerCommand;
import hu.akarnokd.rxjava.interop.RxJavaInterop;
import io.reactivex.Flowable;
import org.particleframework.context.annotation.Primary;
import org.particleframework.context.annotation.Prototype;
import org.particleframework.context.annotation.Replaces;
import org.particleframework.context.annotation.Requires;
import org.particleframework.core.async.publisher.Publishers;
import org.particleframework.core.io.buffer.ByteBuffer;
import org.particleframework.core.type.Argument;
import org.particleframework.http.HttpRequest;
import org.particleframework.http.HttpResponse;
import org.particleframework.http.client.DefaultHttpClient;
import org.particleframework.http.client.HttpClientConfiguration;
import org.particleframework.http.client.LoadBalancer;
import org.particleframework.http.codec.MediaTypeCodecRegistry;
import org.particleframework.http.filter.HttpClientFilter;
import rx.Observable;

import javax.inject.Inject;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Extended version of {@link DefaultHttpClient} adapted to Ribbon
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Prototype
@Requires(classes = IClientConfig.class)
@Primary
@Replaces(DefaultHttpClient.class)
public class RibbonRxHttpClient extends DefaultHttpClient {

    private final RibbonLoadBalancer loadBalancer;
    private final List<? extends ExecutionListener<?, HttpResponse<?>>> executionListeners;

    @Inject
    public RibbonRxHttpClient(
            @org.particleframework.context.annotation.Argument LoadBalancer loadBalancer,
            @org.particleframework.context.annotation.Argument HttpClientConfiguration configuration,
            MediaTypeCodecRegistry codecRegistry,
            RibbonExecutionListenerAdapter[] executionListeners,
            HttpClientFilter... filters) {
        super(loadBalancer, configuration, codecRegistry, filters);
        this.executionListeners = Arrays.asList(executionListeners);
        if (loadBalancer instanceof RibbonLoadBalancer) {
            this.loadBalancer = (RibbonLoadBalancer) loadBalancer;
        } else {
            this.loadBalancer = null;
        }
    }

    /**
     * @return The {@link RibbonLoadBalancer} if one is configured for this client
     */
    public Optional<RibbonLoadBalancer> getLoadBalancer() {
        return Optional.ofNullable(loadBalancer);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <I, O> Flowable<HttpResponse<O>> exchange(HttpRequest<I> request, Argument<O> bodyType) {
        if (loadBalancer != null) {

            LoadBalancerCommand<HttpResponse<O>> loadBalancerCommand = buildLoadBalancerCommand();
            Observable<HttpResponse<O>> requestOperation = loadBalancerCommand.submit(server -> {
                URI newURI = loadBalancer.getLoadBalancerContext().reconstructURIWithServer(server, request.getUri());
                return RxJavaInterop.toV1Observable(
                        Flowable.fromPublisher(Publishers.just(newURI))
                                .switchMap(super.buildExchangePublisher(request, bodyType))
                );
            });

            return RxJavaInterop.toV2Flowable(requestOperation);
        } else {
            return super.exchange(request, bodyType);
        }
    }

    @Override
    public <I> Flowable<HttpResponse<ByteBuffer<?>>> exchangeStream(HttpRequest<I> request) {
        if (loadBalancer != null) {

            LoadBalancerCommand<HttpResponse<ByteBuffer<?>>> loadBalancerCommand = buildLoadBalancerCommand();
            Observable<HttpResponse<ByteBuffer<?>>> requestOperation = loadBalancerCommand.submit(server -> {
                URI newURI = loadBalancer.getLoadBalancerContext().reconstructURIWithServer(server, request.getUri());
                return RxJavaInterop.toV1Observable(
                        Flowable.fromPublisher(Publishers.just(newURI))
                                .switchMap(super.buildExchangeStreamPublisher(request))
                );
            });
            return RxJavaInterop.toV2Flowable(requestOperation);
        } else {
            return super.exchangeStream(request);
        }
    }

    @Override
    public <I> Flowable<ByteBuffer<?>> dataStream(HttpRequest<I> request) {
        if(loadBalancer !=  null) {
            LoadBalancerCommand<ByteBuffer<?>> loadBalancerCommand = buildLoadBalancerCommand();
            Observable<ByteBuffer<?>> requestOperation = loadBalancerCommand.submit(server -> {
                URI newURI = loadBalancer.getLoadBalancerContext().reconstructURIWithServer(server, request.getUri());
                return RxJavaInterop.toV1Observable(
                        Flowable.fromPublisher(Publishers.just(newURI))
                                .switchMap(super.buildDataStreamPublisher(request))
                );
            });
            return RxJavaInterop.toV2Flowable(requestOperation);
        }
        else {
            return super.dataStream(request);
        }
    }

    @Override
    public <I, O> Flowable<O> jsonStream(HttpRequest<I> request, Argument<O> type) {
        if(loadBalancer != null) {
            LoadBalancerCommand<O> loadBalancerCommand = buildLoadBalancerCommand();
            Observable<O> requestOperation = loadBalancerCommand.submit(server -> {
                URI newURI = loadBalancer.getLoadBalancerContext().reconstructURIWithServer(server, request.getUri());
                return RxJavaInterop.toV1Observable(
                        Flowable.fromPublisher(Publishers.just(newURI))
                                .switchMap(super.buildJsonStreamPublisher(request, type))
                );
            });
            return RxJavaInterop.toV2Flowable(requestOperation);
        }
        else {
            return super.jsonStream(request, type);
        }
    }

    protected <O> LoadBalancerCommand<O> buildLoadBalancerCommand() {
        LoadBalancerCommand.Builder<O> commandBuilder = LoadBalancerCommand.builder();
        commandBuilder.withLoadBalancer(loadBalancer.getLoadBalancer())
                .withClientConfig(loadBalancer.getClientConfig());

        if (!executionListeners.isEmpty()) {
            commandBuilder.withListeners((List<? extends ExecutionListener<?, O>>) executionListeners);
        }

        return commandBuilder.build();
    }

}