package co.chainring.contracts.generated;

import io.reactivex.Flowable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes1;
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
import org.web3j.tuples.generated.Tuple7;
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
    public static final String BINARY = "60a06040523060805234801561001457600080fd5b5060805161216061003e60003960008181610ce701528181610d100152610e5101526121606000f3fe6080604052600436106101235760003560e01c806384b0196e116100a0578063c187bdf011610064578063c187bdf0146103d7578063c23f001f146103ed578063c4d66de814610422578063f2fde38b14610442578063f3fef3a31461046257600080fd5b806384b0196e146102d35780638da5cb5b146102fb5780638dc45d9a1461034c57806395ad89e61461036c578063ad3cb1cc1461039957600080fd5b80634f1ef286116100e75780634f1ef2861461024057806352d1902d146102535780635a91f74314610268578063715018a6146102885780637ecebe001461029d57600080fd5b80630d8e6e2c1461018e5780631f186b27146101bb5780632e1a7d4d146101dd5780633644e515146101fd57806347e7ef241461022057600080fd5b366101895733600090815260016020526040812080543492906101479084906118fe565b9091555050604080516000815234602082015233917f5548c837ab068cf56a2c2479df0882a4922fd203edb7517321831d95078c5f62910160405180910390a2005b600080fd5b34801561019a57600080fd5b5060015b6040516001600160401b0390911681526020015b60405180910390f35b3480156101c757600080fd5b506101db6101d6366004611911565b610482565b005b3480156101e957600080fd5b506101db6101f8366004611985565b61054a565b34801561020957600080fd5b50610212610557565b6040519081526020016101b2565b34801561022c57600080fd5b506101db61023b3660046119ba565b610566565b6101db61024e366004611af2565b610660565b34801561025f57600080fd5b5061021261067f565b34801561027457600080fd5b506101db610283366004611b3f565b61069c565b34801561029457600080fd5b506101db610712565b3480156102a957600080fd5b5061019e6102b8366004611b3f565b6002602052600090815260409020546001600160401b031681565b3480156102df57600080fd5b506102e8610726565b6040516101b29796959493929190611baa565b34801561030757600080fd5b507f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c199300546001600160a01b03165b6040516001600160a01b0390911681526020016101b2565b34801561035857600080fd5b50600454610334906001600160a01b031681565b34801561037857600080fd5b50610212610387366004611b3f565b60016020526000908152604090205481565b3480156103a557600080fd5b506103ca604051806040016040528060058152602001640352e302e360dc1b81525081565b6040516101b29190611c43565b3480156103e357600080fd5b5061021260035481565b3480156103f957600080fd5b50610212610408366004611c56565b600060208181529281526040808220909352908152205481565b34801561042e57600080fd5b506101db61043d366004611b3f565b6107d2565b34801561044e57600080fd5b506101db61045d366004611b3f565b610950565b34801561046e57600080fd5b506101db61047d3660046119ba565b61098b565b6004546001600160a01b031633146104e15760405162461bcd60e51b815260206004820152601b60248201527f53656e646572206973206e6f7420746865207375626d6974746572000000000060448201526064015b60405180910390fd5b60005b8181101561052b5736600084848481811061050157610501611c89565b90506020028101906105139190611c9f565b915091506105218282610996565b50506001016104e4565b50818190506003600082825461054191906118fe565b90915550505050565b6105543382610bbd565b50565b6000610561610cd2565b905090565b6040516323b872dd60e01b81523360048201523060248201526044810182905282906001600160a01b038216906323b872dd906064016020604051808303816000875af11580156105bb573d6000803e3d6000fd5b505050506040513d601f19601f820116820180604052508101906105df9190611cec565b50336000908152602081815260408083206001600160a01b0387168452909152812080548492906106119084906118fe565b9091555050604080516001600160a01b03851681526020810184905233917f5548c837ab068cf56a2c2479df0882a4922fd203edb7517321831d95078c5f6291015b60405180910390a2505050565b610668610cdc565b61067182610d81565b61067b8282610d89565b5050565b6000610689610e46565b5060008051602061210b83398151915290565b6106a4610e8f565b6001600160a01b0381166106f05760405162461bcd60e51b81526020600482015260136024820152724e6f7420612076616c6964206164647265737360681b60448201526064016104d8565b600480546001600160a01b0319166001600160a01b0392909216919091179055565b61071a610e8f565b6107246000610eea565b565b600060608082808083816000805160206120eb833981519152805490915015801561075357506001810154155b6107975760405162461bcd60e51b81526020600482015260156024820152741152540dcc4c8e88155b9a5b9a5d1a585b1a5e9959605a1b60448201526064016104d8565b61079f610f5b565b6107a761101e565b60408051600080825260208201909252600f60f81b9c939b5091995046985030975095509350915050565b7ff0c57e16840df040f15088dc2f81fe391c3923bec73e23a9662efc9c229c6a008054600160401b810460ff1615906001600160401b03166000811580156108175750825b90506000826001600160401b031660011480156108335750303b155b905081158015610841575080155b1561085f5760405163f92ee8a960e01b815260040160405180910390fd5b845467ffffffffffffffff19166001178555831561088957845460ff60401b1916600160401b1785555b6108923361105d565b61089a61106e565b6108e76040518060400160405280600e81526020016d436861696e52696e67204c61627360901b81525060405180604001604052806005815260200164302e302e3160d81b815250611076565b600480546001600160a01b0319166001600160a01b038816179055831561094857845460ff60401b19168555604051600181527fc7f505b2f371ae2175ee4913f4499e1f2633a7b5936321eed1cdaeb6115181d29060200160405180910390a15b505050505050565b610958610e8f565b6001600160a01b03811661098257604051631e4fbdf760e01b8152600060048201526024016104d8565b61055481610eea565b61067b338383611088565b6000828260008181106109ab576109ab611c89565b919091013560f81c905060018111156109c6576109c6611d0e565b905060008160018111156109dc576109dc611d0e565b03610ad95760006109f08360018187611d24565b8101906109fd9190611d65565b80518051606090910151919250610a1391611200565b6000610aa36040518060800160405280604281526020016120a960429139805160209182012084518051818401516040808401516060948501518251978801969096526001600160a01b039384169187019190915291169184019190915260808301526001600160401b031660a082015260c0015b604051602081830303815290604052805190602001206112a1565b8251516020840151919250610ab99183906112d4565b815180516020820151604090920151610ad29290611088565b5050505050565b6001816001811115610aed57610aed611d0e565b03610bb8576000610b018360018187611d24565b810190610b0e9190611e2b565b80518051604090910151919250610b2491611200565b6000610b8e60405180606001604052806034815260200161207560349139805160209182012084518051818401516040928301518351958601949094526001600160a01b039091169184019190915260608301526001600160401b0316608082015260a001610a88565b8251516020840151919250610ba49183906112d4565b81518051602090910151610ad29190610bbd565b505050565b6001600160a01b0382166000908152600160205260409020548115610c285781811015610c235760405162461bcd60e51b8152602060048201526014602482015273496e73756666696369656e742042616c616e636560601b60448201526064016104d8565b610c2c565b8091505b6040516001600160a01b0384169083156108fc029084906000818181858888f19350505050158015610c62573d6000803e3d6000fd5b506001600160a01b03831660009081526001602052604081208054849290610c8b908490611ec5565b90915550506040805160008152602081018490526001600160a01b038516917f2717ead6b9200dd235aad468c9809ea400fe33ac69b5bfaa6d3e90fc922b63989101610653565b600061056161133d565b306001600160a01b037f0000000000000000000000000000000000000000000000000000000000000000161480610d6357507f00000000000000000000000000000000000000000000000000000000000000006001600160a01b0316610d5760008051602061210b833981519152546001600160a01b031690565b6001600160a01b031614155b156107245760405163703e46dd60e11b815260040160405180910390fd5b610554610e8f565b816001600160a01b03166352d1902d6040518163ffffffff1660e01b8152600401602060405180830381865afa925050508015610de3575060408051601f3d908101601f19168201909252610de091810190611ed8565b60015b610e0b57604051634c9c8ce360e01b81526001600160a01b03831660048201526024016104d8565b60008051602061210b8339815191528114610e3c57604051632a87526960e21b8152600481018290526024016104d8565b610bb883836113b1565b306001600160a01b037f000000000000000000000000000000000000000000000000000000000000000016146107245760405163703e46dd60e11b815260040160405180910390fd5b33610ec17f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c199300546001600160a01b031690565b6001600160a01b0316146107245760405163118cdaa760e01b81523360048201526024016104d8565b7f9016d09d72d40fdae2fd8ceac6b6234c7706214fd39c1cd1e609a0528c19930080546001600160a01b031981166001600160a01b03848116918217845560405192169182907f8be0079c531659141344cd1fd0a4f28419497f9722a3daafe3b4186f6b6457e090600090a3505050565b7fa16a46d94261c7517cc8ff89f61c0ce93598e3c849801011dee649a6a557d10280546060916000805160206120eb83398151915291610f9a90611ef1565b80601f0160208091040260200160405190810160405280929190818152602001828054610fc690611ef1565b80156110135780601f10610fe857610100808354040283529160200191611013565b820191906000526020600020905b815481529060010190602001808311610ff657829003601f168201915b505050505091505090565b7fa16a46d94261c7517cc8ff89f61c0ce93598e3c849801011dee649a6a557d10380546060916000805160206120eb83398151915291610f9a90611ef1565b611065611407565b61055481611450565b610724611407565b61107e611407565b61067b8282611458565b6001600160a01b038084166000908152602081815260408083209386168352929052205481156110fe57818110156110f95760405162461bcd60e51b8152602060048201526014602482015273496e73756666696369656e742042616c616e636560601b60448201526064016104d8565b611102565b8091505b60405163a9059cbb60e01b81526001600160a01b0385811660048301526024820184905284919082169063a9059cbb906044016020604051808303816000875af1158015611154573d6000803e3d6000fd5b505050506040513d601f19601f820116820180604052508101906111789190611cec565b506001600160a01b03808616600090815260208181526040808320938816835292905290812080548592906111ae908490611ec5565b9091555050604080516001600160a01b038681168252602082018690528716917f2717ead6b9200dd235aad468c9809ea400fe33ac69b5bfaa6d3e90fc922b6398910160405180910390a25050505050565b6001600160a01b038216600090815260026020526040812080546001600160401b03169161122d83611f2b565b91906101000a8154816001600160401b0302191690836001600160401b031602179055506001600160401b0316816001600160401b03161461067b5760405162461bcd60e51b815260206004820152600d60248201526c496e76616c6964204e6f6e636560981b60448201526064016104d8565b60006112ce6112ae610cd2565b8360405161190160f01b8152600281019290925260228201526042902090565b92915050565b60006112e083836114b9565b9050836001600160a01b0316816001600160a01b0316146113375760405162461bcd60e51b8152602060048201526011602482015270496e76616c6964205369676e617475726560781b60448201526064016104d8565b50505050565b60007f8b73c3c69bb8fe3d512ecc4cf759cc79239f7b179b0ffacaa9a75d522b39400f6113686114e3565b61137061154d565b60408051602081019490945283019190915260608201524660808201523060a082015260c00160405160208183030381529060405280519060200120905090565b6113ba82611591565b6040516001600160a01b038316907fbc7cd75a20ee27fd9adebab32041f755214dbc6bffa90cc0225b39da2e5c2d3b90600090a28051156113ff57610bb882826115f6565b61067b61166c565b7ff0c57e16840df040f15088dc2f81fe391c3923bec73e23a9662efc9c229c6a0054600160401b900460ff1661072457604051631afcd79f60e31b815260040160405180910390fd5b610958611407565b611460611407565b6000805160206120eb8339815191527fa16a46d94261c7517cc8ff89f61c0ce93598e3c849801011dee649a6a557d10261149a8482611f99565b50600381016114a98382611f99565b5060008082556001909101555050565b6000806000806114c9868661168b565b9250925092506114d982826116d8565b5090949350505050565b60006000805160206120eb833981519152816114fd610f5b565b80519091501561151557805160209091012092915050565b81548015611524579392505050565b7fc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470935050505090565b60006000805160206120eb8339815191528161156761101e565b80519091501561157f57805160209091012092915050565b60018201548015611524579392505050565b806001600160a01b03163b6000036115c757604051634c9c8ce360e01b81526001600160a01b03821660048201526024016104d8565b60008051602061210b83398151915280546001600160a01b0319166001600160a01b0392909216919091179055565b6060600080846001600160a01b0316846040516116139190612058565b600060405180830381855af49150503d806000811461164e576040519150601f19603f3d011682016040523d82523d6000602084013e611653565b606091505b5091509150611663858383611791565b95945050505050565b34156107245760405163b398979f60e01b815260040160405180910390fd5b600080600083516041036116c55760208401516040850151606086015160001a6116b7888285856117f0565b9550955095505050506116d1565b50508151600091506002905b9250925092565b60008260038111156116ec576116ec611d0e565b036116f5575050565b600182600381111561170957611709611d0e565b036117275760405163f645eedf60e01b815260040160405180910390fd5b600282600381111561173b5761173b611d0e565b0361175c5760405163fce698f760e01b8152600481018290526024016104d8565b600382600381111561177057611770611d0e565b0361067b576040516335e2f38360e21b8152600481018290526024016104d8565b6060826117a6576117a1826118bf565b6117e9565b81511580156117bd57506001600160a01b0384163b155b156117e657604051639996b31560e01b81526001600160a01b03851660048201526024016104d8565b50805b9392505050565b600080807f7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a084111561182b57506000915060039050826118b5565b604080516000808252602082018084528a905260ff891692820192909252606081018790526080810186905260019060a0016020604051602081039080840390855afa15801561187f573d6000803e3d6000fd5b5050604051601f1901519150506001600160a01b0381166118ab575060009250600191508290506118b5565b9250600091508190505b9450945094915050565b8051156118cf5780518082602001fd5b604051630a12f52160e11b815260040160405180910390fd5b634e487b7160e01b600052601160045260246000fd5b808201808211156112ce576112ce6118e8565b6000806020838503121561192457600080fd5b82356001600160401b038082111561193b57600080fd5b818501915085601f83011261194f57600080fd5b81358181111561195e57600080fd5b8660208260051b850101111561197357600080fd5b60209290920196919550909350505050565b60006020828403121561199757600080fd5b5035919050565b80356001600160a01b03811681146119b557600080fd5b919050565b600080604083850312156119cd57600080fd5b6119d68361199e565b946020939093013593505050565b634e487b7160e01b600052604160045260246000fd5b604080519081016001600160401b0381118282101715611a1c57611a1c6119e4565b60405290565b604051608081016001600160401b0381118282101715611a1c57611a1c6119e4565b604051606081016001600160401b0381118282101715611a1c57611a1c6119e4565b600082601f830112611a7757600080fd5b81356001600160401b0380821115611a9157611a916119e4565b604051601f8301601f19908116603f01168101908282118183101715611ab957611ab96119e4565b81604052838152866020858801011115611ad257600080fd5b836020870160208301376000602085830101528094505050505092915050565b60008060408385031215611b0557600080fd5b611b0e8361199e565b915060208301356001600160401b03811115611b2957600080fd5b611b3585828601611a66565b9150509250929050565b600060208284031215611b5157600080fd5b6117e98261199e565b60005b83811015611b75578181015183820152602001611b5d565b50506000910152565b60008151808452611b96816020860160208601611b5a565b601f01601f19169290920160200192915050565b60ff60f81b881681526000602060e06020840152611bcb60e084018a611b7e565b8381036040850152611bdd818a611b7e565b606085018990526001600160a01b038816608086015260a0850187905284810360c08601528551808252602080880193509091019060005b81811015611c3157835183529284019291840191600101611c15565b50909c9b505050505050505050505050565b6020815260006117e96020830184611b7e565b60008060408385031215611c6957600080fd5b611c728361199e565b9150611c806020840161199e565b90509250929050565b634e487b7160e01b600052603260045260246000fd5b6000808335601e19843603018112611cb657600080fd5b8301803591506001600160401b03821115611cd057600080fd5b602001915036819003821315611ce557600080fd5b9250929050565b600060208284031215611cfe57600080fd5b815180151581146117e957600080fd5b634e487b7160e01b600052602160045260246000fd5b60008085851115611d3457600080fd5b83861115611d4157600080fd5b5050820193919092039150565b80356001600160401b03811681146119b557600080fd5b600060208284031215611d7757600080fd5b81356001600160401b0380821115611d8e57600080fd5b9083019081850360a0811215611da357600080fd5b611dab6119fa565b6080821215611db957600080fd5b611dc1611a22565b9150611dcc8461199e565b8252611dda6020850161199e565b602083015260408401356040830152611df560608501611d4e565b606083015290815260808301359082821115611e1057600080fd5b611e1c87838601611a66565b60208201529695505050505050565b600060208284031215611e3d57600080fd5b81356001600160401b0380821115611e5457600080fd5b908301908185036080811215611e6957600080fd5b611e716119fa565b6060821215611e7f57600080fd5b611e87611a44565b9150611e928461199e565b825260208401356020830152611eaa60408501611d4e565b604083015290815260608301359082821115611e1057600080fd5b818103818111156112ce576112ce6118e8565b600060208284031215611eea57600080fd5b5051919050565b600181811c90821680611f0557607f821691505b602082108103611f2557634e487b7160e01b600052602260045260246000fd5b50919050565b60006001600160401b03808316818103611f4757611f476118e8565b6001019392505050565b601f821115610bb8576000816000526020600020601f850160051c81016020861015611f7a5750805b601f850160051c820191505b8181101561094857828155600101611f86565b81516001600160401b03811115611fb257611fb26119e4565b611fc681611fc08454611ef1565b84611f51565b602080601f831160018114611ffb5760008415611fe35750858301515b600019600386901b1c1916600185901b178555610948565b600085815260208120601f198616915b8281101561202a5788860151825594840194600190910190840161200b565b50858210156120485787850151600019600388901b60f8161c191681555b5050505050600190811b01905550565b6000825161206a818460208701611b5a565b919091019291505056fe576974686472617728616464726573732073656e6465722c75696e7432353620616d6f756e742c75696e743634206e6f6e636529576974686472617728616464726573732073656e6465722c6164647265737320746f6b656e2c75696e7432353620616d6f756e742c75696e743634206e6f6e636529a16a46d94261c7517cc8ff89f61c0ce93598e3c849801011dee649a6a557d100360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbca26469706673582212206dbf0e33310a5a8ece77319ab4c9bf8a0812ba0847b2dbfad84000bc32244a8364736f6c63430008180033";

    public static final String FUNC_DOMAIN_SEPARATOR = "DOMAIN_SEPARATOR";

    public static final String FUNC_UPGRADE_INTERFACE_VERSION = "UPGRADE_INTERFACE_VERSION";

    public static final String FUNC_BALANCES = "balances";

    public static final String FUNC_DEPOSIT = "deposit";

    public static final String FUNC_EIP712DOMAIN = "eip712Domain";

    public static final String FUNC_GETVERSION = "getVersion";

    public static final String FUNC_INITIALIZE = "initialize";

    public static final String FUNC_NATIVEBALANCES = "nativeBalances";

    public static final String FUNC_NONCES = "nonces";

    public static final String FUNC_OWNER = "owner";

    public static final String FUNC_PROXIABLEUUID = "proxiableUUID";

    public static final String FUNC_RENOUNCEOWNERSHIP = "renounceOwnership";

    public static final String FUNC_SETSUBMITTER = "setSubmitter";

    public static final String FUNC_SUBMITTRANSACTIONS = "submitTransactions";

    public static final String FUNC_SUBMITTER = "submitter";

    public static final String FUNC_TRANSFEROWNERSHIP = "transferOwnership";

    public static final String FUNC_TXPROCESSEDCOUNT = "txProcessedCount";

    public static final String FUNC_UPGRADETOANDCALL = "upgradeToAndCall";

    public static final String FUNC_withdraw = "withdraw";

    public static final Event DEPOSIT_EVENT = new Event("Deposit", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Address>() {}, new TypeReference<Uint256>() {}));
    ;

    public static final Event EIP712DOMAINCHANGED_EVENT = new Event("EIP712DomainChanged", 
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

    public RemoteFunctionCall<byte[]> DOMAIN_SEPARATOR() {
        final Function function = new Function(FUNC_DOMAIN_SEPARATOR, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Bytes32>() {}));
        return executeRemoteCallSingleValueReturn(function, byte[].class);
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

    public RemoteFunctionCall<Tuple7<byte[], String, String, BigInteger, String, byte[], List<BigInteger>>> eip712Domain() {
        final Function function = new Function(FUNC_EIP712DOMAIN, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Bytes1>() {}, new TypeReference<Utf8String>() {}, new TypeReference<Utf8String>() {}, new TypeReference<Uint256>() {}, new TypeReference<Address>() {}, new TypeReference<Bytes32>() {}, new TypeReference<DynamicArray<Uint256>>() {}));
        return new RemoteFunctionCall<Tuple7<byte[], String, String, BigInteger, String, byte[], List<BigInteger>>>(function,
                new Callable<Tuple7<byte[], String, String, BigInteger, String, byte[], List<BigInteger>>>() {
                    @Override
                    public Tuple7<byte[], String, String, BigInteger, String, byte[], List<BigInteger>> call() throws Exception {
                        List<Type> results = executeCallMultipleValueReturn(function);
                        return new Tuple7<byte[], String, String, BigInteger, String, byte[], List<BigInteger>>(
                                (byte[]) results.get(0).getValue(), 
                                (String) results.get(1).getValue(), 
                                (String) results.get(2).getValue(), 
                                (BigInteger) results.get(3).getValue(), 
                                (String) results.get(4).getValue(), 
                                (byte[]) results.get(5).getValue(), 
                                convertToNative((List<Uint256>) results.get(6).getValue()));
                    }
                });
    }

    public RemoteFunctionCall<BigInteger> getVersion() {
        final Function function = new Function(FUNC_GETVERSION, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint64>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<TransactionReceipt> initialize(String _submitter) {
        final Function function = new Function(
                FUNC_INITIALIZE, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, _submitter)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> nativeBalances(String param0) {
        final Function function = new Function(FUNC_NATIVEBALANCES, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<BigInteger> nonces(String param0) {
        final Function function = new Function(FUNC_NONCES, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, param0)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint64>() {}));
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

    public RemoteFunctionCall<TransactionReceipt> setSubmitter(String _submitter) {
        final Function function = new Function(
                FUNC_SETSUBMITTER, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, _submitter)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> submitTransactions(List<byte[]> transactions) {
        final Function function = new Function(
                FUNC_SUBMITTRANSACTIONS, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.DynamicArray<org.web3j.abi.datatypes.DynamicBytes>(
                        org.web3j.abi.datatypes.DynamicBytes.class,
                        org.web3j.abi.Utils.typeMap(transactions, org.web3j.abi.datatypes.DynamicBytes.class))), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<String> submitter() {
        final Function function = new Function(FUNC_SUBMITTER, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<TransactionReceipt> transferOwnership(String newOwner) {
        final Function function = new Function(
                FUNC_TRANSFEROWNERSHIP, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Address(160, newOwner)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> txProcessedCount() {
        final Function function = new Function(FUNC_TXPROCESSEDCOUNT, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
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

    public static List<EIP712DomainChangedEventResponse> getEIP712DomainChangedEvents(TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(EIP712DOMAINCHANGED_EVENT, transactionReceipt);
        ArrayList<EIP712DomainChangedEventResponse> responses = new ArrayList<EIP712DomainChangedEventResponse>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            EIP712DomainChangedEventResponse typedResponse = new EIP712DomainChangedEventResponse();
            typedResponse.log = eventValues.getLog();
            responses.add(typedResponse);
        }
        return responses;
    }

    public static EIP712DomainChangedEventResponse getEIP712DomainChangedEventFromLog(Log log) {
        Contract.EventValuesWithLog eventValues = staticExtractEventParametersWithLog(EIP712DOMAINCHANGED_EVENT, log);
        EIP712DomainChangedEventResponse typedResponse = new EIP712DomainChangedEventResponse();
        typedResponse.log = log;
        return typedResponse;
    }

    public Flowable<EIP712DomainChangedEventResponse> eIP712DomainChangedEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(log -> getEIP712DomainChangedEventFromLog(log));
    }

    public Flowable<EIP712DomainChangedEventResponse> eIP712DomainChangedEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(EIP712DOMAINCHANGED_EVENT));
        return eIP712DomainChangedEventFlowable(filter);
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

    public static class EIP712DomainChangedEventResponse extends BaseEventResponse {
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
