/*
 This file is part of GNU Taler
 (C) 2020 Taler Systems S.A.

 GNU Taler is free software; you can redistribute it and/or modify it under the
 terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3, or (at your option) any later version.

 GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

import React from 'react';
import { Menu, Button } from 'antd';
import { connect } from 'react-redux';
import { LogoutOutlined } from '@ant-design/icons';
import normalLogo from './libeufin-logo-normal.png';
import './NavBar.less';
import { logout } from '../../actions/auth';

import history from '../../history';

interface Props {
  logoutConnect: () => void;
}

const NavBar = ({ logoutConnect }: Props) => {
  const handleClick = (key) => {
    switch (key) {
      case '1':
        history.push('/home');
        break;
      case '2':
        history.push('/activity');
        break;
      case '3':
        history.push('/bank-accounts');
        break;
      default:
        return undefined;
    }
    return undefined;
  };

  return (
    <div className="navBar">
      <img className="logo" src={normalLogo} alt="LibEuFin normal logo" />
      <Menu
        className="menu"
        mode="horizontal"
        selectedKeys={[]}
        onClick={({ key }) => handleClick(key)}
      >
        <Menu.Item key="1">Home</Menu.Item>
        <Menu.Item key="2">Activity</Menu.Item>
        <Menu.Item key="3">Bank Accounts</Menu.Item>
      </Menu>
      <Button
        type="primary"
        shape="circle"
        icon={<LogoutOutlined />}
        size="large"
        onClick={logoutConnect}
      />
    </div>
  );
};

const mapDispatchToProps = {
  logoutConnect: logout,
};

export default connect(null, mapDispatchToProps)(NavBar);
