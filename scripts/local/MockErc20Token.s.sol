// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.13;

import {Script, console} from "forge-std/Script.sol";
import {ERC20Mock} from "openzeppelin-contracts/contracts/mocks/token/ERC20Mock.sol";

contract MockERC20TokenScript is Script {
    string [] internal walletAddresses = [
    "f39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
    "70997970C51812dc3A010C7d01b50e0d17dc79C8",
    "3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
    "90F79bf6EB2c4f870365E785982E1f101E93b906",
    "15d34AAf54267DB7D7c367839AAf71A00a2C6A65",
    "9965507D1a55bcC2695C58ba16FB37d819B0A4dc",
    "976EA74026E726554dB657fA54763abd0C3a0aa9",
    "14dC79964da2C08b23698B3D3cc7Ca32193d9955",
    "23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f",
    "a0Ee7A142d267C1f36714E4a8F75612F20a79720"
    ];

    function setUp() public {}

    function run() public {
        uint amount = 200000e18;
        vm.startBroadcast();
        ERC20Mock token = new ERC20Mock();
        console.log("{\"deployAddress\": \"%s\"}", address(token));
        for (uint i = 0; i < walletAddresses.length; i++) {
            token.mint(vm.parseAddress(walletAddresses[i]), amount);
        }
    }
}
