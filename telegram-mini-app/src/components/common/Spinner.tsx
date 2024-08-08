import logo from 'assets/funkybit-orange-logo.png'

export default function Spinner() {
  return (
    <div role="status" className="flex place-content-center">
      <img
        className="size-16 origin-[28px_32px] animate-spin"
        src={logo}
        alt="funkybit"
      />
      <span className="sr-only">Loading...</span>
    </div>
  )
}
