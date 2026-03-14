## MODIFIED Requirements

### Requirement: Draining gateways do not accept new ownership
An `access-gateway` instance in drain mode MUST reject new bind ownership while continuing to serve already bound connections until the configured grace period ends. In Kubernetes-native deployment, entering drain mode MUST also transition the gateway out of readiness for new ingress before pod termination proceeds.

#### Scenario: new bind hits draining gateway
- **WHEN** a client attempts to bind on a gateway instance that is already in drain mode
- **THEN** the gateway rejects the bind ownership attempt so the client reconnects to another gateway instance

#### Scenario: gateway becomes unready before Kubernetes termination
- **WHEN** a Kubernetes-hosted gateway pod begins rollout or scale-in termination
- **THEN** the gateway stops reporting readiness for new ingress before its termination grace period is used to drain existing bound connections
