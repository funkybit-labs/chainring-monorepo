import logo from 'assets/logo.svg'

export default function Spinner() {
  return (
    <div role="status" className="flex place-content-center">
      <img className="size-16 animate-spin" src={logo} alt="ChainRing" />
      <span className="sr-only">Loading...</span>
    </div>
  )
}
