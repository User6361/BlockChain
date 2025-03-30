# Blockchain Project in Java

A simple, yet powerful blockchain implementation in Java. This project demonstrates the core principles of a blockchain, including block creation, transaction validation, and consensus mechanisms. The goal is to showcase how blockchain technology works under the hood.

## Features

- **Block Creation**: Each block contains a list of transactions, a timestamp, and a reference to the previous block.
- **Proof of Work**: Basic mining algorithm to validate and add new blocks to the chain.
- **Transaction Validation**: Ensures that the data within each block is consistent and valid.
- **Simple API**: Interact with the blockchain through a simple set of methods for adding blocks and checking the chain's validity.
- **Persistence**: Optionally, store the blockchain data to ensure it persists across application restarts.

## Technologies Used

- **Java 11+**: The core programming language used for implementing the blockchain logic.
- **Gradle**: Used for dependency management and building the project.
- **JUnit 5**: For unit testing the various components of the blockchain system.
- **Jackson**: For serialization and deserialization of blockchain data.

## Getting Started

Follow the steps below to get started with this project:

### Prerequisites

- **Java 11+**: Ensure that you have Java 11 or later installed on your machine.
- **Gradle**: If you don't have Gradle installed globally, you can use the Gradle Wrapper (`gradlew`).

### Clone the Repository

To clone this repository, run the following command in your terminal:

```bash
git clone https://github.com/User6361/BlockChain.git
