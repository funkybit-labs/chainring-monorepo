FROM rust:latest

WORKDIR /usr/src/app

# Install Foundry
RUN curl -L https://foundry.paradigm.xyz | bash
RUN /root/.foundry/bin/foundryup -v nightly-c2e529786c07ee7069cefcd4fe2db41f0e46cef6

# initialize directory for efs mount
RUN mkdir -p /data

EXPOSE 8545

# Entrypoint
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
