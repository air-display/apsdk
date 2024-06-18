/* 
 *  File: ap_casting_media_data_store.h
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

#pragma once
#include <map>
#include <memory>
#include <stack>
#include <string>
#include <vector>

#include <asio.hpp>

#include <ap_config.h>

namespace aps {
namespace service {
class ap_casting_media_data_store {
  /// <summary>
  ///
  /// </summary>
  enum app_id {
    e_app_youtube = 0,
    e_app_netflix = 1,
    e_app_unknown = (uint32_t)-1,
  };

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *MLHLS_SCHEME = "mlhls://";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *NFHLS_SCHEME = "nfhls://";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *NFLX_VIDEO = "nflxvideo";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *SCHEME_LIST = "mlhls://|nfhls://";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *HOST_LIST = "localhost|127\\.0\\.0\\.1";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *MLHLS_HOST = "mlhls://localhost";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *MASTER_M3U8 = "master.m3u8";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *INDEX_M3U8 = "index.m3u8";

  /// <summary>
  ///
  /// </summary>
  static constexpr const char *HTTP_SCHEME = "http://";

  /// <summary>
  ///
  /// </summary>
  typedef std::map<std::string, std::string> media_data;

public:
  static ap_casting_media_data_store &get();

  void set_store_root(uint16_t port);

  // request media data from client side
  bool request_media_data(const std::string &primary_uri, const std::string &session_id);

  // generate and store the media data
  std::string process_media_data(const std::string &uri, const std::string &data);

  // serve the media data for player
  std::string query_media_data(const std::string &path);

  void reset();

protected:
  static app_id get_appi_id(const std::string &uri);

  void add_media_data(const std::string &uri, const std::string &data);

  static bool is_primary_data_uri(const std::string &uri);

  void send_fcup_request(const std::string &uri);

  std::string adjust_primary_uri(const std::string &uri);

  std::string extract_uri_path(const std::string &uri);

  std::string adjust_primary_media_data(const std::string &data);

  static std::string adjust_secondary_media_data(const std::string &data);

  // For Youtube
  std::string adjust_mlhls_data(const std::string &data);

  // For Netflix
  std::string adjust_nfhls_data(const std::string &data);

  ap_casting_media_data_store();

  ~ap_casting_media_data_store();

private:
  app_id app_id_;
  uint32_t request_id_;
  std::string session_id_;
  std::string primary_uri_;
  std::stack<std::string> uri_stack_;

  std::string host_;
  media_data media_data_;
  std::mutex mtx_;
};
} // namespace service
} // namespace aps
