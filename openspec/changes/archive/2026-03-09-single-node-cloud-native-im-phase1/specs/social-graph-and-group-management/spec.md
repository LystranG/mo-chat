## ADDED Requirements

### Requirement: Friend request lifecycle
The system MUST support friend requests where a sender creates a request and the receiver must explicitly accept it before a friendship becomes active.

The friend request record MUST include a `sign` field as a base64 string. The server MUST treat `sign` as opaque data and MUST NOT validate the signature.

#### Scenario: send friend request
- **WHEN** a user submits a friend request to another user
- **THEN** the system stores a friend request record with status `pending` and includes the provided base64 `sign` field

#### Scenario: accept friend request
- **WHEN** the receiver accepts a pending friend request
- **THEN** the system creates a friendship relation with status `ok` and marks the request as `accepted`

#### Scenario: reject friend request
- **WHEN** the receiver rejects a pending friend request
- **THEN** the system marks the request as `rejected` and does not create a friendship relation

### Requirement: Block and unblock behavior
The system MUST support user blocking. When one side blocks, the relationship MUST be treated as blocked for both directions (neither side can send messages to the other). The friendship record MUST store `blocked_by` to identify the blocker.

#### Scenario: user blocks a friendship
- **WHEN** a user blocks an existing friendship
- **THEN** the friendship status becomes `blocked` and `blocked_by` identifies the blocking side

#### Scenario: blocked messaging is rejected
- **WHEN** a user attempts to send a private chat message to a user where the friendship is `blocked`
- **THEN** the server rejects the message with an error response and does not publish it to RocketMQ

### Requirement: Group ownership and membership lifecycle
The system MUST allow users to create groups. A group MUST have an owner id, and group membership MUST be modeled as a (groupId, userId) relation.

#### Scenario: create group
- **WHEN** a user creates a group
- **THEN** the system persists a group record with owner id and creates an active membership for the owner

#### Scenario: join group requires approval
- **WHEN** a user requests to join a group
- **THEN** the system stores a join request record with status `pending` and includes the provided base64 `sign` field

#### Scenario: owner accepts join request
- **WHEN** the group owner accepts a pending join request
- **THEN** the system creates an active group membership for the requester and marks the join request as `accepted`

#### Scenario: owner kicks a member
- **WHEN** the group owner kicks a member from the group
- **THEN** the membership is removed or marked inactive and the kicked user cannot send group messages

#### Scenario: owner dissolves group
- **WHEN** the group owner dissolves the group
- **THEN** the group is marked deleted or removed and memberships are no longer valid

### Requirement: Relationship validation before message acceptance
The server MUST validate relationship constraints before accepting messages for publish.

#### Scenario: private message requires active friendship
- **WHEN** a user sends a private message to another user who is not an active friend
- **THEN** the server rejects the message with an error response and does not publish it to RocketMQ

#### Scenario: group message requires active membership
- **WHEN** a user sends a group message to a group where the user is not an active member
- **THEN** the server rejects the message with an error response and does not publish it to RocketMQ

### Requirement: Social graph and group management HTTP APIs
The system MUST expose HTTP APIs to support friend list, group list, friend request listing, join request listing, and management operations required by this capability.

#### Scenario: list friends
- **WHEN** a logged-in user requests their friend list
- **THEN** the system returns all active friendships for that user
