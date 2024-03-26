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
 * <p>Generated with web3j version 1.5.0.
 */
@SuppressWarnings("rawtypes")
public class Exchange extends Contract {
    public static final String BINARY = "60a06040523060805234801561001457600080fd5b50608051610f6961003e600039600081816107e70152818161081001526109560152610f696000f3fe6080604052600436106100c65760003560e01c80638129fc1c1161007f578063ad3cb1cc11610059578063ad3cb1cc14610268578063c23f001f146102a6578063f2fde38b146102db578063f3fef3a3146102fb57600080fd5b80638129fc1c146101df5780638da5cb5b146101f457806395ad89e61461023b57600080fd5b80630d8e6e2c146101315780632e1a7d4d1461015257806347e7ef24146101745780634f1ef2861461019457806352d1902d146101a7578063715018a6146101ca57600080fd5b3661012c5733600090815260016020526040812080543492906100ea908490610cba565b9091555050604080516000815234602082015233917f5548c837ab068cf56a2c2479df0882a4922fd203edb7517321831d95078c5f62910160405180910390a2005b600080fd5b34801561013d57600080fd5b50604051600181526020015b60405180910390f35b34801561015e57600080fd5b5061017261016d366004610ccd565b61031b565b005b34801561018057600080fd5b5061017261018f366004610d02565b6103f5565b6101726101a2366004610d42565b6104ee565b3480156101b357600080fd5b506101bc61050d565b604051908152602001610149565b3480156101d657600080fd5b5061017261052a565b3480156101eb57600080fd5b5061017261053e565b34801561020057600080fd5b507f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c199300546040516001600160a01b039091168152602001610149565b34801561024757600080fd5b506101bc610256366004610e04565b60016020526000908152604090205481565b34801561027457600080fd5b50610299604051806040016040528060058152602001640352e302e360dc1b81525081565b6040516101499190610e43565b3480156102b257600080fd5b506101bc6102c1366004610e76565b600060208181529281526040808220909352908152205481565b3480156102e757600080fd5b506101726102f6366004610e04565b610655565b34801561030757600080fd5b50610172610316366004610d02565b610693565b33600090815260016020526040902054811561035f578181101561035a57604051638831c26960e01b8152600481018290526024015b60405180910390fd5b610363565b8091505b604051339083156108fc029084906000818181858888f19350505050158015610390573d6000803e3d6000fd5b5033600090815260016020526040812080548492906103b0908490610ea9565b909155505060408051600081526020810184905233917f2717ead6b9200dd235aad468c9809ea400fe33ac69b5bfaa6d3e90fc922b6398910160405180910390a25050565b6040516323b872dd60e01b81523360048201523060248201526044810182905282906001600160a01b038216906323b872dd906064016020604051808303816000875af115801561044a573d6000803e3d6000fd5b505050506040513d601f19601f8201168201806040525081019061046e9190610ebc565b50336000908152602081815260408083206001600160a01b0387168452909152812080548492906104a0908490610cba565b9091555050604080516001600160a01b03851681526020810184905233917f5548c837ab068cf56a2c2479df0882a4922fd203edb7517321831d95078c5f62910160405180910390a2505050565b6104f66107dc565b6104ff82610881565b6105098282610889565b5050565b600061051761094b565b50600080516020610f1483398151915290565b610532610994565b61053c60006109ef565b565b7ff0c57e16840df040f15088dc2f81fe391c3923bec73e23a9662efc9c229c6a008054600160401b810460ff16159067ffffffffffffffff166000811580156105845750825b905060008267ffffffffffffffff1660011480156105a15750303b155b9050811580156105af575080155b156105cd5760405163f92ee8a960e01b815260040160405180910390fd5b845467ffffffffffffffff1916600117855583156105f757845460ff60401b1916600160401b1785555b61060033610a60565b610608610a71565b831561064e57845460ff60401b19168555604051600181527fc7f505b2f371ae2175ee4913f4499e1f2633a7b5936321eed1cdaeb6115181d29060200160405180910390a15b5050505050565b61065d610994565b6001600160a01b03811661068757604051631e4fbdf760e01b815260006004820152602401610351565b610690816109ef565b50565b336000908152602081815260408083206001600160a01b038616845290915290205481156106e457818110156106df57604051638831c26960e01b815260048101829052602401610351565b6106e8565b8091505b60405163a9059cbb60e01b81523360048201526024810183905283906001600160a01b0382169063a9059cbb906044016020604051808303816000875af1158015610737573d6000803e3d6000fd5b505050506040513d601f19601f8201168201806040525081019061075b9190610ebc565b50336000908152602081815260408083206001600160a01b03881684529091528120805485929061078d908490610ea9565b9091555050604080516001600160a01b03861681526020810185905233917f2717ead6b9200dd235aad468c9809ea400fe33ac69b5bfaa6d3e90fc922b6398910160405180910390a250505050565b306001600160a01b037f000000000000000000000000000000000000000000000000000000000000000016148061086357507f00000000000000000000000000000000000000000000000000000000000000006001600160a01b0316610857600080516020610f14833981519152546001600160a01b031690565b6001600160a01b031614155b1561053c5760405163703e46dd60e11b815260040160405180910390fd5b610690610994565b816001600160a01b03166352d1902d6040518163ffffffff1660e01b8152600401602060405180830381865afa9250505080156108e3575060408051601f3d908101601f191682019092526108e091810190610ede565b60015b61090b57604051634c9c8ce360e01b81526001600160a01b0383166004820152602401610351565b600080516020610f14833981519152811461093c57604051632a87526960e21b815260048101829052602401610351565b6109468383610a79565b505050565b306001600160a01b037f0000000000000000000000000000000000000000000000000000000000000000161461053c5760405163703e46dd60e11b815260040160405180910390fd5b336109c67f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c199300546001600160a01b031690565b6001600160a01b03161461053c5760405163118cdaa760e01b8152336004820152602401610351565b7f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c19930080546001600160a01b031981166001600160a01b03848116918217845560405192169182907f8be0079c531659141344cd1fd0a4f28419497f9722a3daafe3b4186f6b6457e090600090a3505050565b610a68610acf565b61069081610b18565b61053c610acf565b610a8282610b20565b6040516001600160a01b038316907fbc7cd75a20ee27fd9adebab32041f755214dbc6bffa90cc0225b39da2e5c2d3b90600090a2805115610ac7576109468282610b85565b610509610bfd565b7ff0c57e16840df040f15088dc2f81fe391c3923bec73e23a9662efc9c229c6a0054600160401b900460ff1661053c57604051631afcd79f60e31b815260040160405180910390fd5b61065d610acf565b806001600160a01b03163b600003610b5657604051634c9c8ce360e01b81526001600160a01b0382166004820152602401610351565b600080516020610f1483398151915280546001600160a01b0319166001600160a01b0392909216919091179055565b6060600080846001600160a01b031684604051610ba29190610ef7565b600060405180830381855af49150503d8060008114610bdd576040519150601f19603f3d011682016040523d82523d6000602084013e610be2565b606091505b5091509150610bf2858383610c1c565b925050505b92915050565b341561053c5760405163b398979f60e01b815260040160405180910390fd5b606082610c3157610c2c82610c7b565b610c74565b8151158015610c4857506001600160a01b0384163b155b15610c7157604051639996b31560e01b81526001600160a01b0385166004820152602401610351565b50805b9392505050565b805115610c8b5780518082602001fd5b604051630a12f52160e11b815260040160405180910390fd5b634e487b7160e01b600052601160045260246000fd5b80820180821115610bf757610bf7610ca4565b600060208284031215610cdf57600080fd5b5035919050565b80356001600160a01b0381168114610cfd57600080fd5b919050565b60008060408385031215610d1557600080fd5b610d1e83610ce6565b946020939093013593505050565b634e487b7160e01b600052604160045260246000fd5b60008060408385031215610d5557600080fd5b610d5e83610ce6565b9150602083013567ffffffffffffffff80821115610d7b57600080fd5b818501915085601f830112610d8f57600080fd5b813581811115610da157610da1610d2c565b604051601f8201601f19908116603f01168101908382118183101715610dc957610dc9610d2c565b81604052828152886020848701011115610de257600080fd5b8260208601602083013760006020848301015280955050505050509250929050565b600060208284031215610e1657600080fd5b610c7482610ce6565b60005b83811015610e3a578181015183820152602001610e22565b50506000910152565b6020815260008251806020840152610e62816040850160208701610e1f565b601f01601f19169190910160400192915050565b60008060408385031215610e8957600080fd5b610e9283610ce6565b9150610ea060208401610ce6565b90509250929050565b81810381811115610bf757610bf7610ca4565b600060208284031215610ece57600080fd5b81518015158114610c7457600080fd5b600060208284031215610ef057600080fd5b5051919050565b60008251610f09818460208701610e1f565b919091019291505056fe360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbca264697066735822122036ca5591b4214eb5e4fcbbc040fbadaa408fd79d8c00af04ca36840dfe90891d64736f6c63430008180033";

    public static final String FUNC_UPGRADE_INTERFACE_VERSION = "UPGRADE_INTERFACE_VERSION";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_DEPOSIT = "deposit";

    public static final String FUNC_GETVERSION = "getVersion";

    public static final String FUNC_INITIALIZE = "initialize";

    public static final String FUNC_NATIVEBALANCES = "nativeBalances";

    public static final String FUNC_OWNER = "owner";

    public static final String FUNC_PROXIABLEUUID = "proxiableUUID";

    public static final String FUNC_RENOUNCEOWNERSHIP = "renounceOwnership";

    public static final String FUNC_TRANSFEROWNERSHIP = "transferOwnership";

    public static final String FUNC_UPGRADETOANDCALL = "upgradeToAndCall";

    public static final String FUNC_withdraw = "withdraw";

    public static final Event DEPOSIT_EVENT = new Event("Deposit", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Address>() {}, new TypeReference<Uint256>() {}));
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

    public static final Event WITHDRAWAL_EVENT = new Event("Withdrawal", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Address>() {}, new TypeReference<Uint256>() {}));
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

    public RemoteFunctionCall<BigInteger> nativeBalances(String param0) {
        final Function function = new Function(FUNC_NATIVEBALANCES, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
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

    public RemoteFunctionCall<TransactionReceipt> withdraw(BigInteger _amount) {
        final Function function = new Function(
                FUNC_withdraw, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Uint256(_amount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> withdraw(String _token, BigInteger _amount) {
        final Function function = new Function(
                FUNC_withdraw, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, _token), 
                new org.web3j.abi.datatypes.generated.Uint256(_amount)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public static List<DepositEventResponse> getDepositEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(DEPOSIT_EVENT, transactionReceipt);
        ArrayList<DepositEventResponse> responses = new ArrayList<DepositEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            DepositEventResponse typedResponse = new DepositEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.from = (String) eventValues.getIndexedValues().get(0).getValue();
            typedResponse.token = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.amount = (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static DepositEventResponse getDepositEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(DEPOSIT_EVENT, log);
        DepositEventResponse typedResponse = new DepositEventResponse();
        typedResponse.log = log;
        typedResponse.from = (String) eventValues.getIndexedValues().get(0).getValue();
        typedResponse.token = (String) eventValues.getNonIndexedValues().get(0).getValue();
        typedResponse.amount = (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
        return typedResponse;
    }

    public Flowable<DepositEventResponse> depositEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getDepositEventFromLog(log));
    }

    public Flowable<DepositEventResponse> depositEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(DEPOSIT_EVENT));
        return depositEventFlowable(filter);
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

    public static List<WithdrawalEventResponse> getWithdrawalEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(WITHDRAWAL_EVENT, transactionReceipt);
        ArrayList<WithdrawalEventResponse> responses = new ArrayList<WithdrawalEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            WithdrawalEventResponse typedResponse = new WithdrawalEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.to = (String) eventValues.getIndexedValues().get(0).getValue();
            typedResponse.token = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.amount = (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static WithdrawalEventResponse getWithdrawalEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(WITHDRAWAL_EVENT, log);
        WithdrawalEventResponse typedResponse = new WithdrawalEventResponse();
        typedResponse.log = log;
        typedResponse.to = (String) eventValues.getIndexedValues().get(0).getValue();
        typedResponse.token = (String) eventValues.getNonIndexedValues().get(0).getValue();
        typedResponse.amount = (BigInteger) eventValues.getNonIndexedValues().get(1).getValue();
        return typedResponse;
    }

    public Flowable<WithdrawalEventResponse> withdrawalEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getWithdrawalEventFromLog(log));
    }

    public Flowable<WithdrawalEventResponse> withdrawalEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(WITHDRAWAL_EVENT));
        return withdrawalEventFlowable(filter);
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

    public static class DepositEventResponse extends BaseEventResponse {
        public String from;

        public String token;

        public BigInteger amount;
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

    public static class WithdrawalEventResponse extends BaseEventResponse {
        public String to;

        public String token;

        public BigInteger amount;
    }
}
