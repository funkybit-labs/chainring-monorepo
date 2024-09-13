import { Button } from 'components/common/Button'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AddressSchema, AdminMarket, AdminSymbol, apiClient } from 'apiClient'
import { Fragment, useMemo, useState } from 'react'
import SubmitButton from 'components/common/SubmitButton'
import Trash from 'assets/Trash.svg'
import SymbolModal from 'components/Screens/Admin/SymbolModal'
import Edit from 'assets/Edit.svg'
import Input from 'components/Screens/Admin/Input'
import Add from 'assets/Add.svg'
import MarketModal from 'components/Screens/Admin/MarketModal'
import { useWallets } from 'contexts/walletProvider'
import { FEE_RATE_PIPS_MAX_VALUE } from 'utils'

export default function Admin({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const chains = useMemo(() => {
    if (configQuery.data) {
      return configQuery.data.chains
    } else {
      return []
    }
  }, [configQuery.data])

  const feeRates = useMemo(() => {
    if (configQuery.data) {
      return configQuery.data.feeRates
    } else {
      return { maker: 0n, taker: 0n }
    }
  }, [configQuery.data])

  const adminsQuery = useQuery({
    queryKey: ['admins'],
    queryFn: apiClient.listAdmins
  })

  const admins = useMemo(() => {
    return adminsQuery.data ?? []
  }, [adminsQuery.data])

  const symbolsQuery = useQuery({
    queryKey: ['symbols'],
    queryFn: apiClient.listSymbols
  })

  const symbols = useMemo(() => {
    return symbolsQuery.data ?? []
  }, [symbolsQuery.data])

  const marketsQuery = useQuery({
    queryKey: ['markets'],
    queryFn: apiClient.listMarkets
  })

  const markets = useMemo(() => {
    return marketsQuery.data ?? []
  }, [marketsQuery.data])

  const wallets = useWallets()

  const [makerFeeRate, setMakerFeeRate] = useState<bigint>()
  const [takerFeeRate, setTakerFeeRate] = useState<bigint>()

  const applyFeeRates = useMutation({
    mutationFn: async () => {
      await apiClient.setFeeRates({
        maker: makerFeeRate!.valueOf(),
        taker: takerFeeRate!.valueOf()
      })
    },
    onError: () => {
      setTimeout(applyFeeRates.reset, 3000)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['configuration'] })
      setMakerFeeRate(undefined)
      setTakerFeeRate(undefined)
    }
  })

  const canApplyFeeRates = useMemo(() => {
    return takerFeeRate !== undefined && makerFeeRate !== undefined
  }, [takerFeeRate, makerFeeRate])

  const [adminAddress, setAdminAddress] = useState<string>()

  const addAdmin = useMutation({
    mutationFn: async () => {
      await apiClient.addAdmin(undefined, {
        params: { address: AddressSchema.parse(adminAddress!) }
      })
    },
    onError: () => {
      setTimeout(addAdmin.reset, 3000)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admins'] })
      setAdminAddress(undefined)
    }
  })

  const [adminAddressToRemove, setAdminAddressToRemove] = useState<string>()

  const removeAdmin = useMutation({
    mutationFn: async () => {
      await apiClient.removeAdmin(undefined, {
        params: { address: AddressSchema.parse(adminAddressToRemove!) }
      })
    },
    onError: () => {
      setTimeout(removeAdmin.reset, 3000)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admins'] })
      setAdminAddressToRemove(undefined)
    }
  })

  const [showSymbolModal, setShowSymbolModal] = useState(false)
  const [selectedSymbol, setSelectedSymbol] = useState<AdminSymbol>()

  const [showMarketModal, setShowMarketModal] = useState(false)
  const [selectedMarket, setSelectedMarket] = useState<AdminMarket>()

  function headerRow() {
    return (
      <div className="flex min-w-full flex-row items-center justify-between">
        <Button
          caption={() => <>Back</>}
          onClick={() => onClose()}
          disabled={false}
          style={'normal'}
          width={'normal'}
        />
        <div className="text-lg">funkybit admin</div>
        <div className="text-sm">{wallets.primary?.address}</div>
      </div>
    )
  }

  function chainsRow() {
    return (
      <div className="m-4 flex flex-row items-center justify-between rounded-lg bg-darkBluishGray8 p-4">
        <div>Chains:</div>
        {chains.map((chain) => {
          return (
            <fieldset
              key={chain.id}
              className="rounded-lg border-2 border-white bg-darkBluishGray6 p-4"
            >
              <legend>{chain.name}</legend>
              ID: {chain.id}
            </fieldset>
          )
        })}
      </div>
    )
  }

  function feeRatesRow() {
    return (
      <div className="m-4 flex flex-row items-center justify-between rounded-lg bg-darkBluishGray8 p-4">
        <div>Fee Rates:</div>
        <fieldset className="rounded-lg border-2 border-white bg-darkBluishGray6 p-4">
          <legend>Maker</legend>
          <Input
            disabled={false}
            placeholder={feeRates.maker.toString() + ' pips'}
            value={makerFeeRate?.toString() ?? ''}
            onChange={(v) => {
              if (v == '') {
                setMakerFeeRate(undefined)
              } else {
                setMakerFeeRate(BigInt(v))
              }
            }}
          />
          <div className="mt-2 text-sm">
            1 pip = {(1 / FEE_RATE_PIPS_MAX_VALUE) * 100}%
          </div>
        </fieldset>
        <fieldset className="rounded-lg border-2 border-white bg-darkBluishGray6 p-4">
          <legend>Taker</legend>
          <Input
            disabled={false}
            placeholder={feeRates.taker.toString() + ' pips'}
            value={takerFeeRate?.toString() ?? ''}
            onChange={(v) => {
              if (v == '') {
                setTakerFeeRate(undefined)
              } else {
                setTakerFeeRate(BigInt(v))
              }
            }}
          />
          <div className="mt-2 text-sm">
            1 pip = {(1 / FEE_RATE_PIPS_MAX_VALUE) * 100}%
          </div>
        </fieldset>
        <div className="w-20">
          <SubmitButton
            caption={() => 'Apply'}
            onClick={() => applyFeeRates.mutate()}
            disabled={!canApplyFeeRates}
            status={canApplyFeeRates ? applyFeeRates.status : 'pending'}
            error={applyFeeRates.error?.message}
          />
        </div>
      </div>
    )
  }

  function adminsRow() {
    return (
      <div className="m-4 flex flex-row flex-wrap items-center justify-between rounded-lg bg-darkBluishGray8 p-4">
        <div>Admins:</div>
        <div>
          {admins.map((admin) => (
            <div
              key={admin}
              className="flex flex-row items-center justify-between space-x-2"
            >
              <span>{admin}</span>
              <img
                alt="Remove"
                className="inline cursor-pointer"
                src={Trash}
                onClick={() => {
                  setAdminAddressToRemove(admin)
                  removeAdmin.mutate()
                }}
              />
            </div>
          ))}
        </div>
        <div>
          <div className="flex flex-row items-center justify-between">
            <Input
              disabled={false}
              placeholder={'Admin address'}
              value={adminAddress ?? ''}
              onChange={(v) => {
                if (v == '') {
                  setAdminAddress(undefined)
                } else {
                  setAdminAddress(v)
                }
              }}
            />
            <div className="ml-4 w-20">
              <SubmitButton
                className="mt-0"
                caption={() => 'Add'}
                onClick={() => addAdmin.mutate()}
                disabled={(adminAddress ?? '') == ''}
                status={
                  (adminAddress ?? '') != '' ? addAdmin.status : 'pending'
                }
                error={addAdmin.error?.message}
              />
            </div>
          </div>
        </div>
      </div>
    )
  }

  function symbolsRow() {
    return (
      <div className="m-4 flex min-w-min flex-row justify-start space-x-8 rounded-lg bg-darkBluishGray8 p-4">
        <div className="flex flex-col items-center">
          Symbols:
          <img
            className="cursor-pointer"
            src={Add}
            alt="Add"
            onClick={() => {
              setSelectedSymbol(undefined)
              setShowSymbolModal(true)
            }}
          />
        </div>
        <div className="grid w-full grid-cols-[repeat(8,min-content)]">
          <div className="mr-4 font-bold">Name</div>
          <div className="mr-4 font-bold">Description</div>
          <div className="mr-4 font-bold">Icon</div>
          <div className="mr-4 font-bold">Decimals</div>
          <div className="mr-4 font-bold">Contract Address</div>
          <div className="mr-4 font-bold">Withdrawal Fee</div>
          <div className="mr-4 whitespace-nowrap font-bold">Add To Wallet?</div>
          <div className="font-bold">Edit</div>
          {symbols
            .toSorted((a, b) => a.name.localeCompare(b.name))
            .map((symbol) => (
              <Fragment key={symbol.name}>
                <div className="mr-4">{symbol.name}</div>
                <div className="mr-4">{symbol.description}</div>
                <div className="mr-4">
                  <img className="inline" src={symbol.iconUrl} alt="icon" />
                </div>
                <div className="mr-4">{symbol.decimals}</div>
                <div className="mr-4">{symbol.contractAddress}</div>
                <div className="mr-4">{symbol.withdrawalFee.toString()}</div>
                <div>{symbol.addToWallets ? 'Yes' : 'No'}</div>
                <div>
                  <img
                    className="cursor-pointer"
                    src={Edit}
                    alt="Change"
                    onClick={() => {
                      setSelectedSymbol(symbol)
                      setShowSymbolModal(true)
                    }}
                  />
                </div>
              </Fragment>
            ))}
        </div>
      </div>
    )
  }

  function marketsRow() {
    return (
      <div className="m-4 flex min-w-min flex-row justify-start space-x-8 rounded-lg bg-darkBluishGray8 p-4">
        <div className="flex flex-col items-center">
          Markets:
          <img
            className="cursor-pointer"
            src={Add}
            alt="Add"
            onClick={() => {
              setSelectedMarket(undefined)
              setShowMarketModal(true)
            }}
          />
        </div>
        <div className="grid w-full grid-cols-[repeat(4,min-content)]">
          <div className="mr-4 font-bold">ID</div>
          <div className="mr-4 whitespace-nowrap font-bold">Tick Size</div>
          <div className="mr-4 whitespace-nowrap font-bold">Min Fee</div>
          <div className="font-bold">Edit</div>
          {markets
            .toSorted((a, b) => a.id.localeCompare(b.id))
            .map((market) => (
              <Fragment key={market.id}>
                <div className="mr-4">{market.id}</div>
                <div className="mr-4">{market.tickSize.toString()}</div>
                <div className="mr-4">{market.minFee.toString()}</div>
                <div>
                  <img
                    className="cursor-pointer"
                    src={Edit}
                    alt="Change"
                    onClick={() => {
                      setSelectedMarket(market)
                      setShowMarketModal(true)
                    }}
                  />
                </div>
              </Fragment>
            ))}
        </div>
      </div>
    )
  }

  return (
    <>
      <div className="min-h-screen bg-darkBluishGray10">
        <div className="mx-4 flex min-h-screen min-w-max justify-center py-4 text-lightBluishGray2">
          <div className="w-full laptop:max-w-[1800px]">
            <div className="grid min-w-max grid-cols-1">
              {headerRow()}
              {chainsRow()}
              {feeRatesRow()}
              {adminsRow()}
              {symbolsRow()}
              {marketsRow()}
            </div>
          </div>
        </div>
      </div>
      {showSymbolModal && (
        <SymbolModal
          symbol={selectedSymbol}
          isOpen={showSymbolModal}
          close={() => setShowSymbolModal(false)}
          onClosed={() => {
            queryClient.invalidateQueries({ queryKey: ['symbols'] })
            setSelectedSymbol(undefined)
          }}
        />
      )}
      {showMarketModal && (
        <MarketModal
          market={selectedMarket}
          isOpen={showMarketModal}
          close={() => setShowMarketModal(false)}
          onClosed={() => {
            queryClient.invalidateQueries({ queryKey: ['markets'] })
            setSelectedMarket(undefined)
          }}
        />
      )}
    </>
  )
}
