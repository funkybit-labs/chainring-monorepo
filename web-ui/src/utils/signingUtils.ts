import {
  Address,
  formatUnits,
  Hex,
  TypedData,
  UserRejectedRequestError
} from 'viem'
import { signTypedData, SignTypedDataParameters } from '@wagmi/core'
import { wagmiConfig } from 'wagmiConfig'
import SatsConnect, { RpcErrorCode } from 'sats-connect'
import { ConnectedWallet } from 'contexts/walletProvider'
import TradingSymbol from 'tradingSymbol'
import { addressZero, getDomain, getWithdrawMessage } from 'utils/eip712'
import { evmAddress, OrderSide } from 'apiClient'
import Decimal from 'decimal.js'
import { Market } from 'markets'

export async function bitcoinSignMessage(
  address: string,
  message: string
): Promise<string | null> {
  const result = await SatsConnect.request('signMessage', {
    address,
    message
  })
  if (result.status === 'success') {
    return result.result.signature
  } else if (result.error.code === RpcErrorCode.USER_REJECTION) {
    return null
  } else {
    console.log(result.error)
    throw Error('Failed to sign message with bitcoin wallet')
  }
}

export async function evmSignTypedData<
  const typedData extends TypedData | Record<string, unknown>,
  primaryType extends keyof typedData | 'EIP712Domain'
>(
  parameters: SignTypedDataParameters<typedData, primaryType>
): Promise<Hex | null> {
  try {
    return await signTypedData(wagmiConfig, parameters)
  } catch (e) {
    if (e instanceof UserRejectedRequestError) {
      return null
    } else {
      throw e
    }
  }
}

export async function signWithdrawal(
  symbol: TradingSymbol,
  walletAddress: string,
  exchangeContractAddress: string,
  timestamp: Date,
  amount: bigint
): Promise<string | null> {
  if (symbol.networkType === 'Bitcoin') {
    let formattedAmount =
      amount == 0n ? '0.0' : formatUnits(amount, symbol.decimals)

    formattedAmount = formattedAmount.padEnd(
      formattedAmount.split('.')[0].length + symbol.decimals + 1,
      '0'
    )

    return await bitcoinSignMessage(
      walletAddress,
      `[funkybit] Please sign this message to authorize withdrawal of ${formattedAmount} ${symbol.displayName()} from the exchange to your wallet.\nAddress: ${walletAddress}, Timestamp: ${timestamp.toISOString()}`
    )
  } else {
    const nonce = BigInt(timestamp.getTime())
    return await evmSignTypedData({
      types: {
        EIP712Domain: [
          { name: 'name', type: 'string' },
          { name: 'version', type: 'string' },
          { name: 'chainId', type: 'uint256' },
          { name: 'verifyingContract', type: 'address' }
        ],
        Withdraw: [
          { name: 'sender', type: 'address' },
          { name: 'token', type: 'address' },
          { name: 'amount', type: 'uint256' },
          { name: 'nonce', type: 'uint64' }
        ]
      },
      domain: getDomain(exchangeContractAddress, symbol.chainId),
      primaryType: 'Withdraw',
      message: getWithdrawMessage(
        walletAddress,
        symbol.contractAddress ? symbol.contractAddress : addressZero,
        amount,
        nonce
      )
    })
  }
}

export async function signOrderCreation(
  wallet: ConnectedWallet,
  chainId: number,
  nonce: string,
  exchangeContractAddress: string,
  baseSymbol: TradingSymbol,
  quoteSymbol: TradingSymbol,
  side: OrderSide,
  amount: bigint,
  limitPrice: Decimal | null,
  percentage: number | null
): Promise<string | null> {
  if (wallet.networkType == 'Bitcoin') {
    let formattedAmount
    if (percentage) {
      formattedAmount = `${percentage}% of your `
    } else {
      formattedAmount = formatUnits(amount, baseSymbol.decimals)
      formattedAmount = formattedAmount.padEnd(
        formattedAmount.split('.')[0].length + baseSymbol.decimals + 1,
        '0'
      )
    }

    return await bitcoinSignMessage(
      wallet.address,
      `[funkybit] Please sign this message to authorize a swap. This action will not cost any gas fees.${
        side == 'Buy'
          ? `\nSwap ${formattedAmount} ${quoteSymbol.displayName()} for ${baseSymbol.displayName()}`
          : `\nSwap ${formattedAmount} ${baseSymbol.displayName()} for ${quoteSymbol.displayName()}`
      }${limitPrice ? `\nPrice: ${limitPrice}` : `\nPrice: Market`}\nAddress: ${
        wallet.address
      }, Nonce: ${nonce}`
    )
  } else {
    const limitPriceAsBigInt = limitPrice
      ? BigInt(
          limitPrice
            .mul(new Decimal(10).pow(quoteSymbol.decimals))
            .floor()
            .toFixed(0)
        )
      : 0n

    return percentage
      ? await evmSignTypedData({
          types: {
            EIP712Domain: [
              { name: 'name', type: 'string' },
              { name: 'version', type: 'string' },
              { name: 'chainId', type: 'uint256' },
              { name: 'verifyingContract', type: 'address' }
            ],
            Order: [
              { name: 'sender', type: 'address' },
              { name: 'baseChainId', type: 'uint256' },
              { name: 'baseToken', type: 'address' },
              { name: 'quoteChainId', type: 'uint256' },
              { name: 'quoteToken', type: 'address' },
              { name: 'percentage', type: 'int256' },
              { name: 'price', type: 'uint256' },
              { name: 'nonce', type: 'int256' }
            ]
          },
          domain: getDomain(exchangeContractAddress, chainId),
          primaryType: 'Order',
          message: {
            sender: wallet.address as Address,
            baseChainId: BigInt(baseSymbol.chainId),
            baseToken: evmAddress(baseSymbol.contractAddress ?? addressZero),
            quoteChainId: BigInt(quoteSymbol.chainId),
            quoteToken: evmAddress(quoteSymbol.contractAddress ?? addressZero),
            percentage: BigInt(percentage),
            price: limitPriceAsBigInt,
            nonce: BigInt('0x' + nonce)
          }
        })
      : await evmSignTypedData({
          types: {
            EIP712Domain: [
              { name: 'name', type: 'string' },
              { name: 'version', type: 'string' },
              { name: 'chainId', type: 'uint256' },
              { name: 'verifyingContract', type: 'address' }
            ],
            Order: [
              { name: 'sender', type: 'address' },
              { name: 'baseChainId', type: 'uint256' },
              { name: 'baseToken', type: 'address' },
              { name: 'quoteChainId', type: 'uint256' },
              { name: 'quoteToken', type: 'address' },
              { name: 'amount', type: 'int256' },
              { name: 'price', type: 'uint256' },
              { name: 'nonce', type: 'int256' }
            ]
          },
          domain: getDomain(exchangeContractAddress, chainId),
          primaryType: 'Order',
          message: {
            sender: wallet.address as Address,
            baseChainId: BigInt(baseSymbol.chainId),
            baseToken: evmAddress(baseSymbol.contractAddress ?? addressZero),
            quoteChainId: BigInt(quoteSymbol.chainId),
            quoteToken: evmAddress(quoteSymbol.contractAddress ?? addressZero),
            amount: side == 'Buy' ? amount : -amount,
            price: limitPriceAsBigInt,
            nonce: BigInt('0x' + nonce)
          }
        })
  }
}

export async function signOrderCancellation(
  wallet: ConnectedWallet,
  chainId: number,
  nonce: string,
  exchangeContractAddress: string,
  market: Market,
  side: OrderSide,
  amount: bigint
): Promise<string | null> {
  if (wallet.networkType == 'Bitcoin') {
    let formattedBaseAmount = formatUnits(amount, market.baseSymbol.decimals)

    formattedBaseAmount = formattedBaseAmount.padEnd(
      formattedBaseAmount.split('.')[0].length + market.baseSymbol.decimals + 1,
      '0'
    )

    return await bitcoinSignMessage(
      wallet.address,
      `[funkybit] Please sign this message to authorize order cancellation. This action will not cost any gas fees.${
        side == 'Buy'
          ? `\nSwap ${formattedBaseAmount} ${market.quoteSymbol.displayName()} for ${market.baseSymbol.displayName()}`
          : `\nSwap ${formattedBaseAmount} ${market.baseSymbol.displayName()} for ${market.quoteSymbol.displayName()}`
      }\nAddress: ${wallet.address}, Nonce: ${nonce}`
    )
  } else {
    return await evmSignTypedData({
      types: {
        EIP712Domain: [
          { name: 'name', type: 'string' },
          { name: 'version', type: 'string' },
          { name: 'chainId', type: 'uint256' },
          { name: 'verifyingContract', type: 'address' }
        ],
        CancelOrder: [
          { name: 'sender', type: 'address' },
          { name: 'marketId', type: 'string' },
          { name: 'amount', type: 'int256' },
          { name: 'nonce', type: 'int256' }
        ]
      },
      domain: getDomain(exchangeContractAddress, chainId),
      primaryType: 'CancelOrder',
      message: {
        sender: wallet.address as Address,
        marketId: market.id,
        amount: side == 'Buy' ? amount : -amount,
        nonce: BigInt('0x' + nonce)
      }
    })
  }
}
