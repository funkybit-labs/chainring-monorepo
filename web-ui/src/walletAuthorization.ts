import { UseAccountReturnType as EvmAccount } from 'wagmi'
import { BitcoinAccount } from 'contexts/bitcoin'
import { signAuthToken } from 'contexts/auth'
import { apiClient, noAuthApiClient, NetworkType } from 'apiClient'
import { bitcoinSignMessage, evmSignTypedData } from 'utils/signingUtils'
import { abbreviatedAddress } from 'utils'

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

  const authorizingWalletSignature = await evmSignTypedData({
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
  const walletToAuthorizeNetworkType =
    primaryNetworkType === 'Bitcoin' ? 'Evm' : 'Bitcoin'

  try {
    const walletAddressToAuthorize =
      primaryNetworkType === 'Bitcoin'
        ? evmAccount.address!
        : bitcoinAccount.address

    const accountConfig = await apiClient.getAccountConfiguration()
    const authorizedAddresses = accountConfig.authorizedAddresses
    const previouslyAuthorizedAddress = authorizedAddresses.find(
      (aa) => aa.networkType === walletToAuthorizeNetworkType
    )

    if (previouslyAuthorizedAddress) {
      if (previouslyAuthorizedAddress.address !== walletAddressToAuthorize) {
        disconnectWallet(walletToAuthorizeNetworkType)
        alert(
          `Can't connect another ${walletToAuthorizeNetworkType} wallet. You have been using wallet with address ${abbreviatedAddress(
            previouslyAuthorizedAddress.address,
            previouslyAuthorizedAddress.networkType
          )} previously`
        )
      }
    } else {
      const authorizationParams = await (primaryNetworkType == 'Bitcoin'
        ? signEvmWalletAuthorizationByBitcoinWallet(evmAccount, bitcoinAccount)
        : signBitcoinWalletAuthorizationByEvmWallet(bitcoinAccount, evmAccount))

      if (authorizationParams == null) {
        disconnectWallet(walletToAuthorizeNetworkType)
        return
      }

      await noAuthApiClient.authorizeWallet(
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
    disconnectWallet(walletToAuthorizeNetworkType)
    alert(
      'Something went wrong while authorizing a wallet, please try again or reach out on Discord'
    )
  }
}
