export default function AmountInput({
  value,
  symbol,
  disabled,
  onChange
}: {
  value: string
  symbol: string
  disabled: boolean
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void
}) {
  return (
    <div className="relative rounded-md shadow-sm">
      <input
        className="block w-full rounded-md border-0 py-1.5 pr-20 text-darkGray ring-1 ring-inset ring-mutedGray [appearance:textfield] placeholder:text-mutedGray focus:ring-1 focus:ring-inset focus:ring-mutedGray [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        disabled={disabled}
        placeholder="0"
        value={value}
        onChange={onChange}
      />
      <div className="absolute inset-y-0 right-0 flex items-center">
        <div className="border-0 px-2 text-darkGray">{symbol}</div>
      </div>
    </div>
  )
}
