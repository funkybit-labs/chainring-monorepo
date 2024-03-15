FROM rust:latest

WORKDIR /usr/src/app

# Install Foundry
RUN curl -L https://foundry.paradigm.xyz | bash
RUN /root/.foundry/bin/foundryup

# initialize directory for efs mount
RUN mkdir -p /data

EXPOSE 8545

# Entrypoint
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]