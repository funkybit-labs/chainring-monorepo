import { Fragment } from 'react'
import { Listbox, Transition } from '@headlessui/react'
import { ChevronDownIcon } from '@heroicons/react/20/solid'
import SymbolIcon from 'components/common/SymbolIcon'
import Markets, { Market } from 'markets'
import { classNames } from 'utils'

export function MarketSelector({
  markets,
  selected,
  onChange
}: {
  markets: Markets
  selected: Market
  onChange: (newValue: Market) => void
}) {
  return (
    <div className="min-w-40">
      <Listbox value={selected} onChange={onChange}>
        <div className="relative">
          <Listbox.Button className="relative w-full cursor-default rounded-[20px] bg-darkBluishGray7 py-2 pl-3 pr-10 text-left transition-colors duration-300 ease-in-out hover:bg-darkBluishGray6 hover:text-white">
            <MarketTitle market={selected} alwaysShowLabel={true} />
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
            <Listbox.Options className="absolute mt-1 max-h-60 w-full overflow-auto rounded-[20px] bg-darkBluishGray7 py-1 text-sm shadow-lg ring-1 ring-black/5 focus:outline-none">
              {markets.map((market) => (
                <Listbox.Option
                  key={market.id}
                  className={
                    'relative cursor-default select-none px-4 py-2 hover:bg-darkBluishGray6 hover:text-white'
                  }
                  value={market}
                >
                  {({ selected }) => (
                    <>
                      <div
                        className={`block truncate ${
                          selected ? 'font-bold text-white' : 'font-normal'
                        }`}
                      >
                        <MarketTitle market={market} alwaysShowLabel={true} />
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

export function MarketTitle({
  market,
  alwaysShowLabel
}: {
  market: Market
  alwaysShowLabel: boolean
}) {
  return (
    <div
      className={classNames(
        'flex place-items-center truncate',
        alwaysShowLabel || 'justify-center narrow:justify-normal'
      )}
    >
      <SymbolIcon
        symbol={market.baseSymbol.name}
        className="mr-2 inline-block size-6"
      />
      <SymbolIcon
        symbol={market.quoteSymbol.name}
        className="mr-2 inline-block size-6"
      />
      <span className={classNames(alwaysShowLabel || 'hidden narrow:inline')}>
        {market.baseSymbol.name}
        <span className="">/</span>
        {market.quoteSymbol.name}
      </span>
    </div>
  )
}
