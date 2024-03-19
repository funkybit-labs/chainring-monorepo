import logo from "../../assets/logo.svg";
import logoName from "../../assets/chainring-logo-name.png";
import {Button} from "../common/Button";
import {useWeb3Modal} from "@web3modal/wagmi/react";
import {useAccount} from "wagmi";
import {addressDisplay} from "../../utils";

export function Header() {
  const { open: openWalletConnectModal, close: closeWalletConnection } = useWeb3Modal()
  const account= useAccount()

  return <div className="h-20 w-full bg-neutralGray p-0 flex flex-row justify-between place-items-center">
    <span>
      <img className="m-2 inline-block size-16" src={logo} alt="ChainRing"/>
      <img className="m-2 inline-block w-32 aspect-auto h-max shrink-0 grow-0" src={logoName} alt="ChainRing"/>
    </span>
    <span className="m-2">
      {account.isConnected ?
        <Button caption={() => addressDisplay(account.address ?? "0x")} onClick={() => openWalletConnectModal({view: 'Account'})} />
        :
        <Button caption={() => "Connect Wallet"} onClick={() => openWalletConnectModal({ view: 'Networks' })} />
      }
    </span>
  </div>
}
