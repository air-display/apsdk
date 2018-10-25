#pragma once
#include <memory>
#include <cstdint>
#include <vector>
#include <string>

#include "crypto/aes.hpp"

namespace aps {
    class server_key_chain
    {
    public:
        server_key_chain();
        ~server_key_chain();

        const std::vector<uint8_t>& ed_public_key() const;
        const std::vector<uint8_t>& ed_private_key() const;
        const std::vector<uint8_t>& curve_public_key() const;
        const std::vector<uint8_t>& curve_private_key() const;

    private:
        std::vector<uint8_t> ed_public_key_;
        std::vector<uint8_t> ed_private_key_;
        std::vector<uint8_t> curve_public_key_;
        std::vector<uint8_t> curve_private_key_;
    };

    class ap_crypto
    {
    public:
        ap_crypto();
        ~ap_crypto();

        void init_client_rsa_info(
            const uint8_t* piv, uint64_t iv_len,
            const uint8_t* pkey, uint64_t key_len);

        void init_client_public_keys(
            const uint8_t* pcurve, uint64_t curve_len,
            const uint8_t* ped, uint64_t ed_len);

        void init_pair_verify_aes();

        void sign_pair_signature(std::vector<uint8_t>& sig);

        bool verify_pair_signature(const uint8_t* p, uint64_t len);

        void init_video_stream_aes(
            const uint64_t video_stream_id);

        void decrypt_video_frame(uint8_t* frame, uint64_t len);

        const std::vector<uint8_t>& shared_secret() const;

        const std::vector<uint8_t>& client_rsa_key() const;

        const std::vector<uint8_t>& client_rsa_iv() const;

        const std::vector<uint8_t>& client_ed_public_key() const;

        const std::vector<uint8_t>& client_curve_public_key() const;

        const server_key_chain& server_keys() const;

    private:
        server_key_chain server_;

        std::vector<uint8_t> shared_secret_;

        std::vector<uint8_t> client_rsa_iv_;

        std::vector<uint8_t> client_rsa_key_;

        std::vector<uint8_t> client_ed_public_key_;
        
        std::vector<uint8_t> client_curve_public_key_;

        AES_ctx pair_verify_aes_ctr_ctx;

        AES_ctx video_stream_aes_ctx;
    };
}
