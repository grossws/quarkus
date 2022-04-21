package io.quarkus.opentelemetry.runtime.tracing.grpc;

import static io.quarkus.opentelemetry.runtime.OpenTelemetryConfig.INSTRUMENTATION_NAME;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;

import jakarta.inject.Singleton;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.net.InetSocketAddressNetServerAttributesGetter;
import io.opentelemetry.instrumentation.api.instrumenter.net.NetServerAttributesExtractor;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import io.quarkus.grpc.GlobalInterceptor;

@Singleton
@GlobalInterceptor
public class GrpcTracingServerInterceptor implements ServerInterceptor {
    private final Instrumenter<GrpcRequest, Status> instrumenter;

    public GrpcTracingServerInterceptor(final OpenTelemetry openTelemetry) {
        InstrumenterBuilder<GrpcRequest, Status> builder = Instrumenter.builder(
                openTelemetry,
                INSTRUMENTATION_NAME,
                new GrpcSpanNameExtractor());

        builder.addAttributesExtractor(new GrpcAttributesExtractor())
                .addAttributesExtractor(NetServerAttributesExtractor.create(new GrpcServerNetServerAttributesGetter()))
                .addAttributesExtractor(new GrpcStatusCodeExtractor())
                .setSpanStatusExtractor(new GrpcSpanStatusExtractor());

        this.instrumenter = builder.newServerInstrumenter(new GrpcTextMapGetter());
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            final ServerCall<ReqT, RespT> call, final Metadata headers, final ServerCallHandler<ReqT, RespT> next) {

        GrpcRequest grpcRequest = GrpcRequest.server(call.getMethodDescriptor(), headers, call.getAttributes());
        Context parentContext = Context.current();
        Context spanContext = null;
        Scope scope = null;
        boolean shouldStart = instrumenter.shouldStart(parentContext, grpcRequest);
        if (shouldStart) {
            spanContext = instrumenter.start(parentContext, grpcRequest);
            scope = spanContext.makeCurrent();
        }

        try {
            TracingServerCall<ReqT, RespT> tracingServerCall = new TracingServerCall<>(call, spanContext, grpcRequest);
            return new TracingServerCallListener<>(next.startCall(tracingServerCall, headers), spanContext, grpcRequest);
        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }

    private static class GrpcServerNetServerAttributesGetter extends InetSocketAddressNetServerAttributesGetter<GrpcRequest> {
        @Override
        public InetSocketAddress getAddress(final GrpcRequest grpcRequest) {
            SocketAddress socketAddress = grpcRequest.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            if (socketAddress instanceof InetSocketAddress) {
                return (InetSocketAddress) socketAddress;
            }
            return null;
        }

        @Override
        public String transport(final GrpcRequest grpcRequest) {
            return SemanticAttributes.NetTransportValues.IP_TCP;
        }
    }

    private static class GrpcTextMapGetter implements TextMapGetter<GrpcRequest> {
        @Override
        public Iterable<String> keys(final GrpcRequest carrier) {
            return carrier.getMetadata() != null ? carrier.getMetadata().keys() : Collections.emptySet();
        }

        @Override
        public String get(final GrpcRequest carrier, final String key) {
            if (carrier != null && carrier.getMetadata() != null) {
                return carrier.getMetadata().get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            } else {
                return null;
            }
        }
    }

    private class TracingServerCallListener<ReqT> extends SimpleForwardingServerCallListener<ReqT> {
        private final Context spanContext;
        private final GrpcRequest grpcRequest;

        protected TracingServerCallListener(final ServerCall.Listener<ReqT> delegate, final Context spanContext,
                final GrpcRequest grpcRequest) {
            super(delegate);
            this.spanContext = spanContext;
            this.grpcRequest = grpcRequest;
        }

        @Override
        public void onHalfClose() {
            try {
                super.onHalfClose();
            } catch (Exception e) {
                instrumenter.end(spanContext, grpcRequest, null, e);
                throw e;
            }
        }

        @Override
        public void onCancel() {
            try {
                super.onCancel();
            } catch (Exception e) {
                instrumenter.end(spanContext, grpcRequest, null, e);
                throw e;
            }
            instrumenter.end(spanContext, grpcRequest, Status.CANCELLED, null);
        }

        @Override
        public void onComplete() {
            try {
                super.onComplete();
            } catch (Exception e) {
                instrumenter.end(spanContext, grpcRequest, null, e);
                throw e;
            }
        }

        @Override
        public void onReady() {
            try {
                super.onReady();
            } catch (Exception e) {
                instrumenter.end(spanContext, grpcRequest, null, e);
                throw e;
            }
        }
    }

    private class TracingServerCall<ReqT, RespT> extends SimpleForwardingServerCall<ReqT, RespT> {
        private final Context spanContext;
        private final GrpcRequest grpcRequest;

        public TracingServerCall(final ServerCall<ReqT, RespT> delegate, final Context spanContext,
                final GrpcRequest grpcRequest) {
            super(delegate);
            this.spanContext = spanContext;
            this.grpcRequest = grpcRequest;
        }

        @Override
        public void close(final Status status, final Metadata trailers) {
            try {
                super.close(status, trailers);
            } catch (Exception e) {
                instrumenter.end(spanContext, grpcRequest, null, e);
                throw e;
            }
            instrumenter.end(spanContext, grpcRequest, status, status.getCause());
        }
    }
}
