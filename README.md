# IDS-Project
IDS project Overlay


# Basic Design

## Setup
- read config physical_network_matrix (potentially python)
- optionally read overay_matrix input
- Start Node id channel_in channel_out
    - Adapt pingpong to publish/subscriber patterns to match fan-in fan-out
    - RabbitMQ for phisical connections

## Routing Table
- Columns
    - Target Node
    - Distance to targed
    - Next hop


## Physical Network initialization
- Initialize Routing table with connected neighbors
- Rule 1
    - START signal: 
        - Broadcast own routing table
- Rule 2: 
    - On recieve routing Table:
        - If own routing table changes:
            - broadcast new routing table

## Overlay
- Either by increasing numbers
- Or based on user requirements
- (Optionally) Optimized version
    - for example shortest ring possible

