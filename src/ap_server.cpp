/* 
 *  File: ap_server.cpp
 *  Project: apsdk
 *  Created: Oct 25, 2018
 *  Author: Sheen Tian
 *  
 *  This file is part of apsdk (https://github.com/air-display/apsdk-public) 
 *  Copyright (C) 2018-2024 Sheen Tian 
 *  
 *  apsdk is free software: you can redistribute it and/or modify it under the terms 
 *  of the GNU General Public License as published by the Free Software Foundation, 
 *  either version 3 of the License, or (at your option) any later version.
 *  
 *  apsdk is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU General Public License for more details.
 *  
 *  You should have received a copy of the GNU General Public License along with Foobar. 
 *  If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * apsdk - API for an open-source AirPlay  server
 * Copyright (C) 2018-2023 Sheen Tian
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 */

#include <ctime>
#include <memory>

#include <ap_config.h>
#include <ap_server.h>
#include <mdns/net_service.h>
#include <service/ap_airplay_service.h>
#include <service/ap_casting_media_data_store.h>
#include <service/ap_casting_media_http_service.h>

using namespace aps::service;
using namespace aps::network;

namespace aps {
class ap_server::implementation {
public:
  implementation()
      : airplay_net_service_("_airplay._tcp"), raop_net_service_("_raop._tcp"), airplay_tcp_service_(0),
        ap_casting_media_http_service_(0) {}

  ~implementation() { release_net_service(); }

  void set_config(ap_config_ptr &config) { ap_config_ = config; }

  void set_handler(ap_handler_ptr handler) { ap_handler_ = handler; }

  bool start() {
    if (airplay_tcp_service_)
      return true;

    airplay_tcp_service_ = std::make_shared<ap_airplay_service>(ap_config_, 0);
    if (!airplay_tcp_service_)
      return false;

    airplay_tcp_service_->set_handler(ap_handler_);

    if (!airplay_tcp_service_->start()) {
      airplay_tcp_service_.reset();
      return false;
    } else {
      LOGD() << "AP service running on " << airplay_tcp_service_->port();
    }

    ap_casting_media_http_service_ = std::make_shared<ap_casting_media_http_service>(ap_config_, 0);

    if (!ap_casting_media_http_service_->start()) {
      LOGW() << "Failed to start media service";
    } else {
      ap_casting_media_data_store::get().set_store_root(ap_casting_media_http_service_->port());
      LOGD() << "Media service running on " << ap_casting_media_http_service_->port();
    }

    if (!initialize_net_service()) {
      airplay_tcp_service_->stop();
      airplay_tcp_service_.reset();
      return false;
    }

    return true;
  }

  void stop() {
    release_net_service();

    if (airplay_tcp_service_) {
      airplay_tcp_service_->stop();
      airplay_tcp_service_.reset();
    }
  }

  uint16_t get_service_port() {
    if (airplay_tcp_service_) {
      return airplay_tcp_service_->port();
    }
    return -1;
  }

protected:
  bool initialize_net_service() {
    if (!ap_config_->publishService()) {
      return true;
    }

    airplay_net_service_.add_txt_record("deviceId", ap_config_->macAddress());
    airplay_net_service_.add_txt_record("features", ap_config_->features_hex_string());
    airplay_net_service_.add_txt_record("model", ap_config_->model());
    airplay_net_service_.add_txt_record("srcvers", ap_config_->serverVersion());
    airplay_net_service_.add_txt_record("vv", ap_config_->vv());
    airplay_net_service_.add_txt_record("pi", ap_config_->pi());
    airplay_net_service_.add_txt_record("pk", ap_config_->pk());
    airplay_net_service_.add_txt_record("flags", ap_config_->flags());

    raop_net_service_.add_txt_record("am", ap_config_->model());
    raop_net_service_.add_txt_record("cn", ap_config_->audioCodecs());
    raop_net_service_.add_txt_record("et", ap_config_->encryptionTypes());
    raop_net_service_.add_txt_record("ft", ap_config_->features_hex_string());
    raop_net_service_.add_txt_record("md", ap_config_->metadataTypes());
    raop_net_service_.add_txt_record("pk", ap_config_->pk());
    raop_net_service_.add_txt_record("tp", ap_config_->transmissionProtocol());
    raop_net_service_.add_txt_record("vs", ap_config_->serverVersion());
    raop_net_service_.add_txt_record("vv", ap_config_->vv());
    raop_net_service_.add_txt_record("vn", "65537");
    raop_net_service_.add_txt_record("da", "true");
    raop_net_service_.add_txt_record("sf", "0x04");

    std::string airplay_service_name = ap_config_->name();
    if (airplay_net_service_.publish(airplay_service_name, airplay_tcp_service_->port())) {
      std::string raop_name = ap_config_->deviceID();
      raop_name += "@";
      raop_name += airplay_service_name;

      if (raop_net_service_.publish(raop_name, airplay_tcp_service_->port()))
        return true;

      airplay_net_service_.suppress();
    }

    return false;
  }

  void release_net_service() {
    if (!ap_config_->publishService()) {
      return;
    }

    airplay_net_service_.suppress();
    raop_net_service_.suppress();
  }

private:
  ap_config_ptr ap_config_;

  ap_handler_ptr ap_handler_;

  net_service airplay_net_service_;

  net_service raop_net_service_;

  ap_airplay_service_ptr airplay_tcp_service_;

  ap_casting_media_http_service_ptr ap_casting_media_http_service_;
};

ap_server::ap_server() : impl_(new implementation()) {}

ap_server::~ap_server() {
  if (impl_) {
    delete impl_;
    impl_ = nullptr;
  }
}

void ap_server::set_config(ap_config_ptr &config) { impl_->set_config(config); }

void ap_server::set_handler(ap_handler_ptr &handler) { impl_->set_handler(handler); }

bool aps::ap_server::start() { return impl_->start(); }

void ap_server::stop() { impl_->stop(); }

uint16_t ap_server::get_service_port() { return impl_->get_service_port(); }

#if __ANDROID__
void ap_server::setJavaVM(JavaVM *vm) { setGlobalJavaVM(vm); }
#endif
} // namespace aps
