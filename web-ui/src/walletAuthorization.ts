import { UseAccountReturnType as EvmAccount } from 'wagmi'
import { BitcoinAccount, bitcoinSignMessage } from 'contexts/bitcoin'
import { signAuthToken } from 'auth'
import { UserRejectedRequestError } from 'viem'
import { signTypedData as evmSignTypedData } from '@wagmi/core'
import { wagmiConfig } from 'wagmiConfig'
import { apiClient, authorizeWalletApiClient, NetworkType } from 'apiClient'

export type WalletAuthorizationParams = {
  authorizedAddress: string
  authorizedWalletAuthToken: string
  authorizingWalletAddress: string
  authorizingWalletChainId: number
  authorizingWalletSignature: string
  timestamp: string
}

export async function signEvmWalletAuthorizationByBitcoinWallet(
  evmAccount: EvmAccount,
  bitcoinAccount: BitcoinAccount
): Promise<WalletAuthorizationParams | null> {
  const timestamp = new Date().toISOString()
  const authorizingWalletAddress = bitcoinAccount.address
  const authorizingWalletChainId = 0
  const authorizedAddress = evmAccount.address!
  const authorizedWalletAuthToken = await signAuthToken(
    authorizedAddress,
    evmAccount.chainId!
  )
  if (authorizedWalletAuthToken == null) {
    return null
  }

  const authorizingWalletSignature = await bitcoinSignMessage(
    authorizingWalletAddress,
    `[funkybit] Please sign this message to authorize EVM wallet ${authorizedAddress.toLowerCase()}. This action will not cost any gas fees.\nAddress: ${authorizingWalletAddress}, Timestamp: ${timestamp}`
  )

  if (authorizingWalletSignature == null) {
    return null
  }

  return {
    authorizedAddress,
    authorizedWalletAuthToken,
    authorizingWalletAddress,
    authorizingWalletChainId,
    authorizingWalletSignature,
    timestamp
  }
}

export async function signBitcoinWalletAuthorizationByEvmWallet(
  bitcoinAccount: BitcoinAccount,
  evmAccount: EvmAccount
): Promise<WalletAuthorizationParams | null> {
  const timestamp = new Date().toISOString()
  const authorizedAddress = bitcoinAccount.address
  const authorizingWalletAddress = evmAccount.address!
  const authorizingWalletChainId = evmAccount.chainId!

  const authorizedWalletAuthToken = await signAuthToken(authorizedAddress, 0)
  if (authorizedWalletAuthToken == null) {
    return null
  }

  const authorizingWalletSignature = await evmSignTypedData(wagmiConfig, {
    domain: {
      name: 'funkybit',
      chainId: authorizingWalletChainId
    },
    types: {
      EIP712Domain: [
        { name: 'name', type: 'string' },
        { name: 'chainId', type: 'uint32' }
      ],
      Authorize: [
        { name: 'message', type: 'string' },
        { name: 'address', type: 'string' },
        { name: 'authorizedAddress', type: 'string' },
        { name: 'chainId', type: 'uint32' },
        { name: 'timestamp', type: 'string' }
      ]
    },
    message: {
      message: `[funkybit] Please sign this message to authorize Bitcoin wallet ${authorizedAddress}. This action will not cost any gas fees.`,
      address: authorizingWalletAddress,
      authorizedAddress: authorizedAddress,
      chainId: authorizingWalletChainId,
      timestamp: timestamp
    },
    primaryType: 'Authorize'
  }).catch((e) => {
    if (e instanceof UserRejectedRequestError) {
      return null
    } else {
      throw e
    }
  })

  if (authorizingWalletSignature == null) {
    return null
  }

  return {
    authorizedAddress,
    authorizedWalletAuthToken,
    authorizingWalletAddress,
    authorizingWalletChainId,
    authorizingWalletSignature,
    timestamp
  }
}

export async function authorizeWallet(
  primaryNetworkType: NetworkType,
  bitcoinAccount: BitcoinAccount,
  evmAccount: EvmAccount,
  disconnectWallet: (networkType: NetworkType) => void
) {
  try {
    const accountConfig = await apiClient.getAccountConfiguration()
    if (accountConfig.authorizedAddresses.length == 0) {
      let authorizationParams
      if (primaryNetworkType == 'Bitcoin') {
        authorizationParams = await signEvmWalletAuthorizationByBitcoinWallet(
          evmAccount,
          bitcoinAccount
        )
        if (authorizationParams == null) {
          disconnectWallet('Evm')
          return
        }
      } else {
        authorizationParams = await signBitcoinWalletAuthorizationByEvmWallet(
          bitcoinAccount,
          evmAccount
        )
        if (authorizationParams == null) {
          disconnectWallet('Bitcoin')
          return
        }
      }

      await authorizeWalletApiClient.authorizeWallet(
        {
          authorizedAddress: authorizationParams.authorizedAddress,
          chainId: authorizationParams.authorizingWalletChainId,
          address: authorizationParams.authorizingWalletAddress,
          timestamp: authorizationParams.timestamp,
          signature: authorizationParams.authorizingWalletSignature
        },
        {
          headers: {
            Authorization: `Bearer ${authorizationParams.authorizedWalletAuthToken}`
          }
        }
      )
    }
  } catch (error) {
    console.log('Error during wallet authorization', error)
    disconnectWallet(primaryNetworkType == 'Bitcoin' ? 'Evm' : 'Bitcoin')
    alert(
      'Something went wrong while authorizing a wallet, please try again or reach out on Discord'
    )
  }
}
