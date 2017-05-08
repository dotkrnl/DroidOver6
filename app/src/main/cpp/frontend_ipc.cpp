//
// Created by tansinan on 5/8/17.
//

#include "frontend_ipc.h"
#include <unistd.h>
#include <arpa/inet.h>
#include <stdint.h>

int tunFd = -1;

int handle_frontend_command(int commandPipeFd, int responsePipeFd) {
    unsigned char command;
    int ret = read(commandPipeFd, &command, 1);
    if(ret != 1) return ret;
    if(command == BACKEND_IPC_COMMAND_TERMINATE) {
        return command;
    }
    if (command == BACKEND_IPC_COMMAND_STATUS) {
        write(responsePipeFd, "Alive", 5);
    }
    else if (command == BACKEND_IPC_COMMAND_STATISTICS) {
        int64_t inBytes = 12345;
        int64_t outBytes = 67890;
        write(responsePipeFd, &inBytes, sizeof(inBytes));
        write(responsePipeFd, &outBytes, sizeof(outBytes));
    }
    else if (command == BACKEND_IPC_COMMAND_CONFIGURATION) {
        uint8_t ip[4] = {10, 10, 10, 2};
        uint8_t mask[4] = {255, 255, 255, 0};
        uint8_t dns1[4] = {166, 111, 8, 28};
        uint8_t dns2[4] = {166, 111, 8, 29};
        uint8_t dns3[4] = {166, 111, 8, 30};
        write(responsePipeFd, ip, 4);
        write(responsePipeFd, mask, 4);
        write(responsePipeFd, dns1, 4);
        write(responsePipeFd, dns2, 4);
        write(responsePipeFd, dns3, 4);
    }
    else if (command == BACKEND_IPC_COMMAND_SET_TUNNEL_FD) {
        int ret = read(commandPipeFd, &tunFd, 4);
        if(ret > 0)
            tunFd = ntohl(tunFd);
        else
            tunFd = -100;
    }
    return command;
}
