FROM rust:latest

WORKDIR /usr/src/app

# Install Foundry
RUN curl -L https://foundry.paradigm.xyz | bash
RUN /root/.foundry/bin/foundryup

EXPOSE 8545

# Entrypoint 
CMD ["/root/.foundry/bin/anvil", "--block-time", "1", "--host", "0.0.0.0", "--port", "8545"]
