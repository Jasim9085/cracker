#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pcap.h>
#include <stdbool.h>

// A simple structure to hold a list of SSIDs we've already found,
// to avoid printing duplicates.
#define MAX_SSIDS 128
char found_ssids[MAX_SSIDS][33];
int ssid_count = 0;

// Checks if we have already printed this SSID.
bool already_found(const char* ssid) {
    for (int i = 0; i < ssid_count; i++) {
        if (strcmp(found_ssids[i], ssid) == 0) {
            return true;
        }
    }
    return false;
}

// Adds an SSID to our list of found networks.
void add_ssid(const char* ssid) {
    if (ssid_count < MAX_SSIDS) {
        strncpy(found_ssids[ssid_count], ssid, 32);
        found_ssids[ssid_count][32] = '\0'; // Ensure null termination
        ssid_count++;
    }
}

// Simplified Beacon Frame structure.
struct ieee80211_beacon {
    unsigned char frame_header[24];
    unsigned char fixed_params[12];
    unsigned char tag_number;
    unsigned char tag_length;
};

// The libpcap callback function for each captured packet.
void packet_handler(unsigned char *user_args, const struct pcap_pkthdr *header, const unsigned char *packet) {
    int radiotap_header_length = 26; // A reasonable default guess.

    if (header->len < (radiotap_header_length + sizeof(struct ieee80211_beacon))) {
        return; // Packet is too short, ignore.
    }
    
    // Check for Beacon Frame (Type 0, Subtype 8).
    if (packet[radiotap_header_length] == 0x80) {
        const struct ieee80211_beacon *beacon = (const struct ieee80211_beacon *)(packet + radiotap_header_length);

        if (beacon->tag_number == 0 && beacon->tag_length > 0 && beacon->tag_length < 33) {
            char ssid[33];
            memcpy(ssid, (unsigned char*)beacon + sizeof(struct ieee80211_beacon), beacon->tag_length);
            ssid[beacon->tag_length] = '\0';

            if (!already_found(ssid)) {
                // Print the result to STDOUT. The Java service reads this.
                printf("SSID: %s\n", ssid);
                fflush(stdout);
                add_ssid(ssid);
            }
        }
    }
}

// Helper function to run a command and check its result.
int run_command(const char* command) {
    int result = system(command);
    if (result != 0) {
        // Print errors to STDERR. The Java service also reads this.
        fprintf(stderr, "ERROR: Command failed with code %d: %s\n", result, command);
    }
    return result;
}

int main(int argc, char *argv[]) {
    pcap_t *handle;
    char errbuf[PCAP_ERRBUF_SIZE];
    char *interface_name = "wlan0";
    char monitor_interface[20];
    snprintf(monitor_interface, sizeof(monitor_interface), "%smon", interface_name);

    // --- Step 1: Enable Monitor Mode ---
    fprintf(stderr, "INFO: Attempting to enable monitor mode on %s...\n", interface_name);
    if (run_command("ip link set wlan0 down") != 0) return 1;
    if (run_command("iw dev wlan0 set type monitor") != 0) {
        fprintf(stderr, "FATAL: Could not set monitor mode. Does this device support it?\n");
        run_command("ip link set wlan0 up"); // Try to restore state
        return 1;
    }
    if (run_command("ip link set wlan0 up") != 0) return 1;
    sleep(2); // Give the interface a moment to initialize

    // --- Step 2: Open the Capture Handle ---
    fprintf(stderr, "INFO: Opening capture handle on %s...\n", monitor_interface);
    handle = pcap_open_live(monitor_interface, BUFSIZ, 1, 500, errbuf);
    if (handle == NULL) {
        fprintf(stderr, "FATAL: pcap_open_live failed for %s: %s\n", monitor_interface, errbuf);
        // Attempt to clean up
        run_command("iw dev wlan0mon set type managed");
        return 2;
    }

    // --- Step 3: Run the Capture Loop ---
    fprintf(stderr, "INFO: Capturing packets for 10 seconds...\n");
    int pcap_result = pcap_dispatch(handle, 200, packet_handler, NULL); // Capture up to 200 packets
    if (pcap_result < 0) {
        fprintf(stderr, "ERROR: pcap_dispatch returned an error: %s\n", pcap_geterr(handle));
    }
    pcap_close(handle);
    fprintf(stderr, "INFO: Capture finished.\n");

    // --- Step 4: Disable Monitor Mode ---
    fprintf(stderr, "INFO: Disabling monitor mode...\n");
    run_command("ip link set wlan0mon down");
    run_command("iw dev wlan0mon set type managed");
    run_command("ip link set wlan0 up");

    return 0; // Success
}
