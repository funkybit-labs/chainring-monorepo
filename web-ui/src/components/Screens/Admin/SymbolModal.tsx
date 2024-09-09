import { useConfig } from 'wagmi'
import { ChangeEvent, useEffect, useMemo, useState } from 'react'
import { getToken } from 'wagmi/actions'
import { Modal } from 'components/common/Modal'
import { AdminSymbol, apiClient, evmAddress } from 'apiClient'
import { useMutation } from '@tanstack/react-query'
import Input from 'components/Screens/Admin/Input'
import SubmitButton from 'components/common/SubmitButton'

export default function SymbolModal({
  symbol: initialSymbol,
  isOpen,
  close,
  onClosed
}: {
  symbol?: AdminSymbol
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const config = useConfig()

  const adding = initialSymbol === undefined
  const [symbol, setSymbol] = useState<AdminSymbol>(
    initialSymbol ?? {
      name: '',
      description: '',
      decimals: 0,
      chainId: config.chains[0].id,
      contractAddress: null,
      addToWallets: false,
      withdrawalFee: 0n,
      iconUrl: ''
    }
  )

  const addSymbol = useMutation({
    mutationFn: async () => {
      await apiClient.addSymbol(symbol)
    },
    onError: () => {
      setTimeout(addSymbol.reset, 3000)
    },
    onSuccess: () => {
      close()
      onClosed()
    }
  })

  const patchSymbol = useMutation({
    mutationFn: async () => {
      await apiClient.patchSymbol(symbol, { params: { symbol: symbol.name } })
    },
    onError: () => {
      setTimeout(patchSymbol.reset, 3000)
    },
    onSuccess: () => {
      close()
      onClosed()
    }
  })

  const [contractAddressValue, setContractAddressValue] = useState(
    initialSymbol?.contractAddress ?? ''
  )
  const [contractAddressError, setContractAddressError] = useState('')

  function setContractAddress() {
    if (contractAddressValue != '') {
      try {
        const parsedAddress = evmAddress(contractAddressValue)
        getToken(config, { address: parsedAddress, chainId: symbol.chainId })
          .then((token) => {
            setSymbol({
              ...symbol,
              decimals: token.decimals,
              name: token.symbol ? addChainIfNeeded(token.symbol) : symbol.name,
              description: token.name ?? symbol.description,
              contractAddress: parsedAddress
            })
          })
          .catch((err) => {
            setContractAddressError(err.message)
          })
      } catch (error) {
        if (error instanceof Error) {
          setContractAddressError(error.message)
        } else {
          console.log(error)
        }
      }
    }
  }

  function checkContractAddress() {
    getToken(config, {
      address: evmAddress(symbol.contractAddress!),
      chainId: symbol.chainId
    })
      .then((token) => {
        setSymbol({
          ...symbol,
          decimals: token.decimals,
          name: token.symbol ? addChainIfNeeded(token.symbol) : symbol.name,
          description: token.name ?? symbol.description
        })
      })
      .catch((err) => {
        setContractAddressError(err.message)
      })
  }

  const [selectedIconFile, setSelectedIconFile] = useState<File>()
  const [iconPreview, setIconPreview] = useState<string>()

  const onSelectIconFile = (e: ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) {
      setSelectedIconFile(undefined)
      return
    }

    setSelectedIconFile(e.target.files[0]!)
  }

  useEffect(() => {
    if (selectedIconFile) {
      const objectUrl = URL.createObjectURL(selectedIconFile)
      setIconPreview(objectUrl)
      // free memory when ever this component is unmounted
      return () => URL.revokeObjectURL(objectUrl)
    }
  }, [selectedIconFile])

  function replaceChainInName(name: string, chainId: number) {
    return name.replace(new RegExp(':.*$', ''), `:${chainId}`)
  }

  function addChainIfNeeded(name: string) {
    return name.includes(':')
      ? replaceChainInName(name, symbol.chainId)
      : `${name}:${symbol.chainId}`
  }

  const canSubmit = useMemo(() => {
    return (
      symbol.chainId > 0 &&
      symbol.name != '' &&
      contractAddressError == '' &&
      symbol.iconUrl != ''
    )
  }, [symbol.chainId, symbol.name, contractAddressError, symbol.iconUrl])

  useEffect(() => {
    if (iconPreview) {
      fetch(iconPreview).then((r) => {
        const reader = new FileReader()
        r.blob().then((blob) => {
          reader.readAsDataURL(blob)
          reader.onloadend = () => {
            setSymbol((s) => {
              return { ...s, iconUrl: reader.result!.toString() }
            })
          }
        })
      })
    }
  }, [iconPreview])

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={onClosed}
      title={adding ? `Add Symbol` : `Edit ${symbol.name}`}
    >
      <div className="overflow-y-auto">
        <div className="flex flex-col rounded-lg bg-darkBluishGray8 p-4 text-white">
          <div className="font-bold underline">Chain</div>
          <div>
            <select
              disabled={!adding}
              value={symbol.chainId}
              className="bg-darkBluishGray9"
              onChange={(e) => {
                const chainId = parseInt(e.target.value)
                if (symbol.contractAddress) {
                  checkContractAddress()
                } else {
                  setSymbol({
                    ...symbol,
                    chainId: chainId,
                    name: replaceChainInName(symbol.name, chainId)
                  })
                }
              }}
            >
              {config.chains.map((chain) => {
                return (
                  <option key={chain.id} value={chain.id}>
                    {chain.name}
                  </option>
                )
              })}
            </select>
          </div>
          <div className="mt-2 font-bold underline">Contract Address</div>
          <div>
            <Input
              placeholder={'Contract Address (blank for native)'}
              disabled={!adding}
              value={contractAddressValue}
              onChange={(address) => {
                setContractAddressError('')
                setContractAddressValue(address)
              }}
              onBlur={() => setContractAddress()}
            />
            {contractAddressError && (
              <div className="text-statusRed">{contractAddressError}</div>
            )}
          </div>
          <div className="mt-2 font-bold underline">Name</div>
          <div>
            <Input
              placeholder={'Symbol name, e.g. BTC or BTC:1337'}
              disabled={!adding}
              value={symbol.name}
              onChange={(name) => setSymbol({ ...symbol, name: name })}
              onBlur={() =>
                setSymbol({ ...symbol, name: addChainIfNeeded(symbol.name) })
              }
            />
          </div>
          <div className="mt-2 font-bold underline">Description</div>
          <div>
            <Input
              placeholder={'Symbol description, e.g. Bitcoin'}
              disabled={false}
              value={symbol.description}
              onChange={(description) => setSymbol({ ...symbol, description })}
            />
          </div>
          <div className="mt-2 font-bold underline">Decimals</div>
          <div>
            {symbol.contractAddress ? (
              symbol.decimals
            ) : (
              <Input
                placeholder={'Decimals'}
                disabled={!adding}
                value={symbol.decimals.toString()}
                onChange={(decimals) =>
                  setSymbol({ ...symbol, decimals: parseInt(decimals) })
                }
              />
            )}
          </div>
          <div className="mt-2 font-bold underline">Icon</div>
          <div>
            <div className="space-x-2 whitespace-nowrap">
              {(iconPreview || symbol.iconUrl) && (
                <img
                  className="inline"
                  src={iconPreview ?? symbol.iconUrl}
                  alt="preview"
                />
              )}
              <input type="file" onChange={onSelectIconFile} />
            </div>
          </div>
          <div className="mt-2 font-bold underline">Withdrawal Fee</div>
          <div>
            <Input
              placeholder={'Withdrawal fee, in token units'}
              disabled={false}
              value={
                symbol.withdrawalFee === 0n
                  ? ''
                  : symbol.withdrawalFee.toString()
              }
              onChange={(withdrawalFee) =>
                setSymbol({ ...symbol, withdrawalFee: BigInt(withdrawalFee) })
              }
            />
          </div>
          <div className="mt-2 font-bold underline">Add To Wallets?</div>
          <div className="space-x-2">
            <input
              type="radio"
              name="addToWallets"
              value="1"
              checked={symbol.addToWallets}
              onChange={(e) =>
                setSymbol({ ...symbol, addToWallets: e.target.checked })
              }
            />{' '}
            Yes
            <input
              type="radio"
              name="addToWallets"
              value="0"
              checked={!symbol.addToWallets}
              onChange={(e) =>
                setSymbol({ ...symbol, addToWallets: !e.target.checked })
              }
            />{' '}
            No
          </div>
          <SubmitButton
            disabled={!canSubmit}
            onClick={() => {
              if (adding) {
                addSymbol.mutate()
              } else {
                patchSymbol.mutate()
              }
            }}
            caption={() => (adding ? 'Add' : 'Edit')}
            error={
              adding ? addSymbol.error?.message : patchSymbol.error?.message
            }
            status={adding ? addSymbol.status : patchSymbol.status}
          />
        </div>
      </div>
    </Modal>
  )
}
