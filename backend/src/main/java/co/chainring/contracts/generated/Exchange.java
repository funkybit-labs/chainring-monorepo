package co.chainring.contracts.generated;

import io.reactivex.Flowable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.BaseEventResponse;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

/**
 * <p>Auto generated code.
 * <p><strong>Do not modify!</strong>
 * <p>Please use the <a href="https://docs.web3j.io/command_line.html">web3j command line tools</a>,
 * or the org.web3j.codegen.SolidityFunctionWrapperGenerator in the 
 * <a href="https://github.com/web3j/web3j/tree/master/codegen">codegen module</a> to update.
 *
 * <p>Generated with web3j version 1.5.2.
 */
@SuppressWarnings("rawtypes")
public class Exchange extends Contract {
    public static final String BINARY = "60a06040523060805234801561001457600080fd5b50608051610d5b61003e600039600081816105f20152818161061b01526107610152610d5b6000f3fe60806040526004361061009c5760003560e01c80638129fc1c116100645780638129fc1c1461012f5780638da5cb5b14610144578063ad3cb1cc1461018b578063c23f001f146101c9578063f2fde38b146101fe578063f3fef3a31461021e57600080fd5b80630d8e6e2c146100a157806347e7ef24146100c25780634f1ef286146100e457806352d1902d146100f7578063715018a61461011a575b600080fd5b3480156100ad57600080fd5b50604051600181526020015b60405180910390f35b3480156100ce57600080fd5b506100e26100dd366004610acb565b61023e565b005b6100e26100f2366004610b0b565b61031c565b34801561010357600080fd5b5061010c61033b565b6040519081526020016100b9565b34801561012657600080fd5b506100e2610358565b34801561013b57600080fd5b506100e261036c565b34801561015057600080fd5b507f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c199300546040516001600160a01b0390911681526020016100b9565b34801561019757600080fd5b506101bc604051806040016040528060058152602001640352e302e360dc1b81525081565b6040516100b99190610bf1565b3480156101d557600080fd5b5061010c6101e4366004610c24565b600060208181529281526040808220909352908152205481565b34801561020a57600080fd5b506100e2610219366004610c57565b610483565b34801561022a57600080fd5b506100e2610239366004610acb565b6104c6565b6040516323b872dd60e01b81523360048201523060248201526044810182905282906001600160a01b038216906323b872dd906064016020604051808303816000875af1158015610293573d6000803e3d6000fd5b505050506040513d601f19601f820116820180604052508101906102b79190610c72565b50336000908152602081815260408083206001600160a01b0387168452909152812080548492906102e9908490610caa565b90915550506040517ff455d980ae4b5ce88473b33e7ef9997ae789f5a9c48014d08e71ca4c3aca100490600090a1505050565b6103246105e7565b61032d8261068c565b6103378282610694565b5050565b6000610345610756565b50600080516020610d0683398151915290565b61036061079f565b61036a60006107fa565b565b7ff0c57e16840df040f15088dc2f81fe391c3923bec73e23a9662efc9c229c6a008054600160401b810460ff16159067ffffffffffffffff166000811580156103b25750825b905060008267ffffffffffffffff1660011480156103cf5750303b155b9050811580156103dd575080155b156103fb5760405163f92ee8a960e01b815260040160405180910390fd5b845467ffffffffffffffff19166001178555831561042557845460ff60401b1916600160401b1785555b61042e3361086b565b61043661087c565b831561047c57845460ff60401b19168555604051600181527fc7f505b2f371ae2175ee4913f4499e1f2633a7b5936321eed1cdaeb6115181d29060200160405180910390a15b5050505050565b61048b61079f565b6001600160a01b0381166104ba57604051631e4fbdf760e01b8152600060048201526024015b60405180910390fd5b6104c3816107fa565b50565b336000908152602081815260408083206001600160a01b0386168452909152902054811561050057818110156104fb57600080fd5b610504565b8091505b60405163a9059cbb60e01b81523360048201526024810183905283906001600160a01b0382169063a9059cbb906044016020604051808303816000875af1158015610553573d6000803e3d6000fd5b505050506040513d601f19601f820116820180604052508101906105779190610c72565b50336000908152602081815260408083206001600160a01b0388168452909152812080548592906105a9908490610cbd565b90915550506040518381527fd6f0c229fd8cd3f306eda72697c76f6af102e50e5e642aef98af1e408b0135ae9060200160405180910390a150505050565b306001600160a01b037f000000000000000000000000000000000000000000000000000000000000000016148061066e57507f00000000000000000000000000000000000000000000000000000000000000006001600160a01b0316610662600080516020610d06833981519152546001600160a01b031690565b6001600160a01b031614155b1561036a5760405163703e46dd60e11b815260040160405180910390fd5b6104c361079f565b816001600160a01b03166352d1902d6040518163ffffffff1660e01b8152600401602060405180830381865afa9250505080156106ee575060408051601f3d908101601f191682019092526106eb91810190610cd0565b60015b61071657604051634c9c8ce360e01b81526001600160a01b03831660048201526024016104b1565b600080516020610d06833981519152811461074757604051632a87526960e21b8152600481018290526024016104b1565b6107518383610884565b505050565b306001600160a01b037f0000000000000000000000000000000000000000000000000000000000000000161461036a5760405163703e46dd60e11b815260040160405180910390fd5b336107d17f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c199300546001600160a01b031690565b6001600160a01b03161461036a5760405163118cdaa760e01b81523360048201526024016104b1565b7f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c19930080546001600160a01b031981166001600160a01b03848116918217845560405192169182907f8be0079c531659141344cd1fd0a4f28419497f9722a3daafe3b4186f6b6457e090600090a3505050565b6108736108da565b6104c381610923565b61036a6108da565b61088d8261092b565b6040516001600160a01b038316907fbc7cd75a20ee27fd9adebab32041f755214dbc6bffa90cc0225b39da2e5c2d3b90600090a28051156108d2576107518282610990565b610337610a08565b7ff0c57e16840df040f15088dc2f81fe391c3923bec73e23a9662efc9c229c6a0054600160401b900460ff1661036a57604051631afcd79f60e31b815260040160405180910390fd5b61048b6108da565b806001600160a01b03163b60000361096157604051634c9c8ce360e01b81526001600160a01b03821660048201526024016104b1565b600080516020610d0683398151915280546001600160a01b0319166001600160a01b0392909216919091179055565b6060600080846001600160a01b0316846040516109ad9190610ce9565b600060405180830381855af49150503d80600081146109e8576040519150601f19603f3d011682016040523d82523d6000602084013e6109ed565b606091505b50915091506109fd858383610a27565b925050505b92915050565b341561036a5760405163b398979f60e01b815260040160405180910390fd5b606082610a3c57610a3782610a86565b610a7f565b8151158015610a5357506001600160a01b0384163b155b15610a7c57604051639996b31560e01b81526001600160a01b03851660048201526024016104b1565b50805b9392505050565b805115610a965780518082602001fd5b604051630a12f52160e11b815260040160405180910390fd5b80356001600160a01b0381168114610ac657600080fd5b919050565b60008060408385031215610ade57600080fd5b610ae783610aaf565b946020939093013593505050565b634e487b7160e01b600052604160045260246000fd5b60008060408385031215610b1e57600080fd5b610b2783610aaf565b9150602083013567ffffffffffffffff80821115610b4457600080fd5b818501915085601f830112610b5857600080fd5b813581811115610b6a57610b6a610af5565b604051601f8201601f19908116603f01168101908382118183101715610b9257610b92610af5565b81604052828152886020848701011115610bab57600080fd5b8260208601602083013760006020848301015280955050505050509250929050565b60005b83811015610be8578181015183820152602001610bd0565b50506000910152565b6020815260008251806020840152610c10816040850160208701610bcd565b601f01601f19169190910160400192915050565b60008060408385031215610c3757600080fd5b610c4083610aaf565b9150610c4e60208401610aaf565b90509250929050565b600060208284031215610c6957600080fd5b610a7f82610aaf565b600060208284031215610c8457600080fd5b81518015158114610a7f57600080fd5b634e487b7160e01b600052601160045260246000fd5b80820180821115610a0257610a02610c94565b81810381811115610a0257610a02610c94565b600060208284031215610ce257600080fd5b5051919050565b60008251610cfb818460208701610bcd565b919091019291505056fe360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbca26469706673582212201a7740ab06e82a253f16d14eb835080c32e5a0d14c26a3c5ab13f2a0549a451164736f6c63430008180033";

    public static final String FUNC_UPGRADE_INTERFACE_VERSION = "UPGRADE_INTERFACE_VERSION";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_DEPOSIT = "deposit";

    public static final String FUNC_GETVERSION = "getVersion";

    public static final String FUNC_INITIALIZE = "initialize";

    public static final String FUNC_OWNER = "owner";

    public static final String FUNC_PROXIABLEUUID = "proxiableUUID";

    public static final String FUNC_RENOUNCEOWNERSHIP = "renounceOwnership";

    public static final String FUNC_TRANSFEROWNERSHIP = "transferOwnership";

    public static final String FUNC_UPGRADETOANDCALL = "upgradeToAndCall";

    public static final String FUNC_WITHDRAW = "withdraw";

    public static final Event DEPOSITCREATED_EVENT = new Event("DepositCreated", 
            Arrays.<TypeReference<?>>asList());
    ;

    public static final Event INITIALIZED_EVENT = new Event("Initialized", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Uint64>() {}));
    ;

    public static final Event OWNERSHIPTRANSFERRED_EVENT = new Event("OwnershipTransferred", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Address>(true) {}));
    ;

    public static final Event UPGRADED_EVENT = new Event("Upgraded", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}));
    ;

    public static final Event WITHDRAWALCREATED_EVENT = new Event("WithdrawalCreated", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
    ;

    @Deprecated
    protected Exchange(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    protected Exchange(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    @Deprecated
    protected Exchange(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    protected Exchange(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public RemoteFunctionCall<String> UPGRADE_INTERFACE_VERSION() {
        final Function function = new Function(FUNC_UPGRADE_INTERFACE_VERSION, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<BigInteger> balances(String param0, String param1) {
        final Function function = new Function(FUNC_BALANCES, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0), 
                new org.web3j.abi.datatypes.Address(160, param1)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> deposit(String _token, BigInteger _amount) {
        final Function function = new Function(
                FUNC_DEPOSIT, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, _token), 
                new org.web3j.abi.datatypes.generated.Uint256(_amount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> getVersion() {
        final Function function = new Function(FUNC_GETVERSION, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint64>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> initialize() {
        final Function function = new Function(
                FUNC_INITIALIZE, 
                Arrays.<Type>asList(), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<String> owner() {
        final Function function = new Function(FUNC_OWNER, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<byte[]> proxiableUUID() {
        final Function function = new Function(FUNC_PROXIABLEUUID, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Bytes32>() {}));
        return executeRemoteCallSingleValueReturn(function, byte[].class);
    }

    public RemoteFunctionCall<TransactionReceipt> renounceOwnership() {
        final Function function = new Function(
                FUNC_RENOUNCEOWNERSHIP, 
                Arrays.<Type>asList(), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> transferOwnership(String newOwner) {
        final Function function = new Function(
                FUNC_TRANSFEROWNERSHIP, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, newOwner)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> upgradeToAndCall(String newImplementation, byte[] data, BigInteger weiValue) {
        final Function function = new Function(
                FUNC_UPGRADETOANDCALL, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, newImplementation), 
                new org.web3j.abi.datatypes.DynamicBytes(data)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function, weiValue);
    }

    public RemoteFunctionCall<TransactionReceipt> withdraw(String _token, BigInteger _amount) {
        final Function function = new Function(
                FUNC_WITHDRAW, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, _token), 
                new org.web3j.abi.datatypes.generated.Uint256(_amount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public static List<DepositCreatedEventResponse> getDepositCreatedEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(DEPOSITCREATED_EVENT, transactionReceipt);
        ArrayList<DepositCreatedEventResponse> responses = new ArrayList<DepositCreatedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            DepositCreatedEventResponse typedResponse = new DepositCreatedEventResponse();
            typedResponse.log = eventValues.getLog();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static DepositCreatedEventResponse getDepositCreatedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(DEPOSITCREATED_EVENT, log);
        DepositCreatedEventResponse typedResponse = new DepositCreatedEventResponse();
        typedResponse.log = log;
        return typedResponse;
    }

    public Flowable<DepositCreatedEventResponse> depositCreatedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getDepositCreatedEventFromLog(log));
    }

    public Flowable<DepositCreatedEventResponse> depositCreatedEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(DEPOSITCREATED_EVENT));
        return depositCreatedEventFlowable(filter);
    }

    public static List<InitializedEventResponse> getInitializedEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(INITIALIZED_EVENT, transactionReceipt);
        ArrayList<InitializedEventResponse> responses = new ArrayList<InitializedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            InitializedEventResponse typedResponse = new InitializedEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.version = (BigInteger) eventValues.getNonIndexedValues().get(0).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static InitializedEventResponse getInitializedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(INITIALIZED_EVENT, log);
        InitializedEventResponse typedResponse = new InitializedEventResponse();
        typedResponse.log = log;
        typedResponse.version = (BigInteger) eventValues.getNonIndexedValues().get(0).getValue();
        return typedResponse;
    }

    public Flowable<InitializedEventResponse> initializedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getInitializedEventFromLog(log));
    }

    public Flowable<InitializedEventResponse> initializedEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(INITIALIZED_EVENT));
        return initializedEventFlowable(filter);
    }

    public static List<OwnershipTransferredEventResponse> getOwnershipTransferredEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(OWNERSHIPTRANSFERRED_EVENT, transactionReceipt);
        ArrayList<OwnershipTransferredEventResponse> responses = new ArrayList<OwnershipTransferredEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            OwnershipTransferredEventResponse typedResponse = new OwnershipTransferredEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.previousOwner = (String) eventValues.getIndexedValues().get(0).getValue();
            typedResponse.newOwner = (String) eventValues.getIndexedValues().get(1).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static OwnershipTransferredEventResponse getOwnershipTransferredEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(OWNERSHIPTRANSFERRED_EVENT, log);
        OwnershipTransferredEventResponse typedResponse = new OwnershipTransferredEventResponse();
        typedResponse.log = log;
        typedResponse.previousOwner = (String) eventValues.getIndexedValues().get(0).getValue();
        typedResponse.newOwner = (String) eventValues.getIndexedValues().get(1).getValue();
        return typedResponse;
    }

    public Flowable<OwnershipTransferredEventResponse> ownershipTransferredEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getOwnershipTransferredEventFromLog(log));
    }

    public Flowable<OwnershipTransferredEventResponse> ownershipTransferredEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(OWNERSHIPTRANSFERRED_EVENT));
        return ownershipTransferredEventFlowable(filter);
    }

    public static List<UpgradedEventResponse> getUpgradedEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(UPGRADED_EVENT, transactionReceipt);
        ArrayList<UpgradedEventResponse> responses = new ArrayList<UpgradedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            UpgradedEventResponse typedResponse = new UpgradedEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.implementation = (String) eventValues.getIndexedValues().get(0).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static UpgradedEventResponse getUpgradedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(UPGRADED_EVENT, log);
        UpgradedEventResponse typedResponse = new UpgradedEventResponse();
        typedResponse.log = log;
        typedResponse.implementation = (String) eventValues.getIndexedValues().get(0).getValue();
        return typedResponse;
    }

    public Flowable<UpgradedEventResponse> upgradedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getUpgradedEventFromLog(log));
    }

    public Flowable<UpgradedEventResponse> upgradedEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(UPGRADED_EVENT));
        return upgradedEventFlowable(filter);
    }

    public static List<WithdrawalCreatedEventResponse> getWithdrawalCreatedEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(WITHDRAWALCREATED_EVENT, transactionReceipt);
        ArrayList<WithdrawalCreatedEventResponse> responses = new ArrayList<WithdrawalCreatedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            WithdrawalCreatedEventResponse typedResponse = new WithdrawalCreatedEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.param0 = (BigInteger) eventValues.getNonIndexedValues().get(0).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static WithdrawalCreatedEventResponse getWithdrawalCreatedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(WITHDRAWALCREATED_EVENT, log);
        WithdrawalCreatedEventResponse typedResponse = new WithdrawalCreatedEventResponse();
        typedResponse.log = log;
        typedResponse.param0 = (BigInteger) eventValues.getNonIndexedValues().get(0).getValue();
        return typedResponse;
    }

    public Flowable<WithdrawalCreatedEventResponse> withdrawalCreatedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getWithdrawalCreatedEventFromLog(log));
    }

    public Flowable<WithdrawalCreatedEventResponse> withdrawalCreatedEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(WITHDRAWALCREATED_EVENT));
        return withdrawalCreatedEventFlowable(filter);
    }

    @Deprecated
    public static Exchange load(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return new Exchange(contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    @Deprecated
    public static Exchange load(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return new Exchange(contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    public static Exchange load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return new Exchange(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static Exchange load(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new Exchange(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<Exchange> deploy(Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(Exchange.class, web3j, credentials, contractGasProvider, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<Exchange> deploy(Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(Exchange.class, web3j, credentials, gasPrice, gasLimit, BINARY, "");
    }

    public static RemoteCall<Exchange> deploy(Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(Exchange.class, web3j, transactionManager, contractGasProvider, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<Exchange> deploy(Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(Exchange.class, web3j, transactionManager, gasPrice, gasLimit, BINARY, "");
    }

    public static class DepositCreatedEventResponse extends BaseEventResponse {
    }

    public static class InitializedEventResponse extends BaseEventResponse {
        public BigInteger version;
    }

    public static class OwnershipTransferredEventResponse extends BaseEventResponse {
        public String previousOwner;

        public String newOwner;
    }

    public static class UpgradedEventResponse extends BaseEventResponse {
        public String implementation;
    }

    public static class WithdrawalCreatedEventResponse extends BaseEventResponse {
        public BigInteger param0;
    }
}
