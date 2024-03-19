import { useAccount } from 'wagmi'
import WelcomeScreen from 'components/Screens/WelcomeScreen'
import HomeScreen from 'components/Screens/HomeScreen'

function App() {
  const account = useAccount()

  if (account.isConnected) {
    return <HomeScreen />
  } else {
    return <WelcomeScreen />
  }
}

export default App
