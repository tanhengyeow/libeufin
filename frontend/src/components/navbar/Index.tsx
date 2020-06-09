import * as React from 'react';
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
