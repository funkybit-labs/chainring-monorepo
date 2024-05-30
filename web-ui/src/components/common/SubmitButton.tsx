import { classNames } from 'utils'

export default function SubmitButton({
  disabled,
  onClick,
  caption,
  error
}: {
  disabled: boolean
  onClick: () => void
  caption: () => string
  error: string | null | undefined
}) {
  return (
    <>
      <button
        type="button"
        disabled={disabled}
        className={classNames(
          'mt-4 w-full inline-flex justify-center rounded-[50px] px-4 py-3 text-md text-white focus:outline-none focus:ring-1 focus:ring-inset',
          disabled
            ? 'bg-neutralGray focus:ring-mutedGray'
            : 'bg-blue4 hover:bg-blue4 focus:ring-lightBackground'
        )}
        onClick={onClick}
      >
        {caption()}
      </button>

      {error && (
        <div className="mt-2 text-center text-sm font-bold text-brightRed">
          {error}
        </div>
      )}
    </>
  )
}
