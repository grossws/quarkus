
package io.quarkus.kubernetes.deployment;

import io.dekorate.kubernetes.config.Probe;
import io.dekorate.kubernetes.config.ProbeBuilder;

public class ProbeConverter {

    public static Probe convert(ProbeConfig probe) {
        return builder(probe).build();
    }

    public static ProbeBuilder builder(ProbeConfig probe) {
        ProbeBuilder b = new ProbeBuilder();
        probe.httpActionPath.ifPresent(b::withHttpActionPath);
        probe.execAction.ifPresent(b::withExecAction);
        probe.tcpSocketAction.ifPresent(b::withTcpSocketAction);
        probe.grpcAction.ifPresent(b::withGrpcAction);
        b.withInitialDelaySeconds((int) probe.initialDelay.getSeconds());
        b.withPeriodSeconds((int) probe.period.getSeconds());
        b.withTimeoutSeconds((int) probe.timeout.getSeconds());
        b.withSuccessThreshold(probe.successThreshold);
        b.withFailureThreshold(probe.failureThreshold);
        return b;
    }
}
