import { Fragment, useMemo } from 'react'
import { Listbox, Transition } from '@headlessui/react'
import { ChevronDownIcon } from '@heroicons/react/20/solid'
import SymbolIcon from 'components/common/SymbolIcon'
import Markets from 'markets'
import TradingSymbol from 'tradingSymbol'

export function SymbolSelector({
  markets,
  selected,
  onChange
}: {
  markets: Markets
  selected: TradingSymbol
  onChange: (newSymbol: TradingSymbol) => void
}) {
  const availableSymbols = useMemo(() => {
    return Object.values(
      Object.fromEntries(
        markets.flatMap((m) => [
          [m.baseSymbol.name, m.baseSymbol],
          [m.quoteSymbol.name, m.quoteSymbol]
        ])
      )
    )
  }, [markets])

  return (
    <div>
      <Listbox
        value={selected}
        onChange={(newSymbol) => {
          onChange(newSymbol)
        }}
      >
        <div className="relative">
          <Listbox.Button className="relative flex cursor-default rounded-[32px] bg-swapDropdownBackground py-2 pl-3 pr-10 text-left text-darkBluishGray1 transition-colors duration-300 ease-in-out hover:bg-swapHighlight hover:text-white">
            <SymbolIcon
              symbol={selected}
              className="mr-2 inline-block size-6 align-top"
            />
            <span className="leading-none">
              {selected.name}
              <br />
              <span className="text-xs">{selected.chainName}</span>
            </span>
            <span className="pointer-events-none absolute inset-y-0 right-0 flex items-center pr-2">
              <ChevronDownIcon
                className="size-6 text-darkBluishGray1"
                aria-hidden="true"
              />
            </span>
          </Listbox.Button>
          <Transition
            as={Fragment}
            leave="transition ease-in duration-100"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
          >
            <Listbox.Options className="absolute z-10 mt-1 max-h-72 w-max overflow-auto rounded-[32px] bg-swapDropdownBackground py-1 shadow-lg ring-1 ring-black/5 focus:outline-none">
              {availableSymbols.map((symbol) => (
                <Listbox.Option
                  key={symbol.name}
                  className={
                    'relative cursor-default select-none px-4 py-2 hover:bg-swapHighlight hover:text-white'
                  }
                  value={symbol}
                >
                  {({ selected }) => (
                    <>
                      <div
                        className={`flex truncate py-1 ${
                          selected
                            ? 'font-bold text-white'
                            : 'font-normal text-darkBluishGray1'
                        }`}
                      >
                        <SymbolIcon
                          symbol={symbol}
                          className="mr-2 inline-block size-6 align-top"
                        />
                        <span className="leading-none">
                          {symbol.name}
                          <br />
                          <span className="text-xs">{symbol.chainName}</span>
                        </span>
                      </div>
                    </>
                  )}
                </Listbox.Option>
              ))}
            </Listbox.Options>
          </Transition>
        </div>
      </Listbox>
    </div>
  )
}
