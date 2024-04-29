import { useMemo, useState } from 'react'
import { parseUnits } from 'viem'
import { cleanAndFormatNumberInput } from 'utils'

// custom hook modelling state of input for crypto amounts
export default function useAmountInputState({
  initialInputValue,
  initialValue,
  decimals
}: {
  initialInputValue: string
  initialValue?: bigint
  decimals: number
}): {
  inputValue: string
  setInputValue: (newValue: string) => void
  valueInFundamentalUnits: bigint
} {
  const [inputValue, setInputValue] = useState(initialInputValue)
  const valueInFundamentalUnits = useMemo(() => {
    if (inputValue === initialInputValue) {
      return initialValue === undefined
        ? parseUnits(initialInputValue, decimals)
        : initialValue
    } else {
      return parseUnits(inputValue, decimals)
    }
  }, [inputValue, initialValue, initialInputValue, decimals])

  return {
    inputValue,
    setInputValue: (newValue: string) => {
      setInputValue(cleanAndFormatNumberInput(newValue, decimals))
    },
    valueInFundamentalUnits
  }
}
