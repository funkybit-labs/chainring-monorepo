FROM rust:latest

WORKDIR /usr/src/app

# Install Foundry
RUN curl -L https://foundry.paradigm.xyz | bash
RUN /root/.foundry/bin/foundryup -v nightly-5ac78a9cd4b94dc53d1fe5e0f42372b28b5a7559

# initialize directory for efs mount
RUN mkdir -p /data

EXPOSE 8545

# Entrypoint
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
