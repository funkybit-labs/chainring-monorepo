pragma solidity ^0.8.0;

contract Chainring {

    function whoami() public view returns (address) {
        return msg.sender;
    }

}