export default function AmountInput({
  value,
  disabled,
  onChange
}: {
  value: string
  disabled: boolean
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void
}) {
  return (
    <div className="relative rounded">
      <input
        className="block w-full rounded border-0 bg-darkBluishGray9 py-3 text-center text-white ring-1 ring-inset ring-darkBluishGray6 [appearance:textfield] placeholder:text-darkBluishGray2 focus:ring-1 focus:ring-inset focus:ring-mutedGray [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        disabled={disabled}
        placeholder="0"
        value={value}
        onChange={onChange}
      />
    </div>
  )
}
