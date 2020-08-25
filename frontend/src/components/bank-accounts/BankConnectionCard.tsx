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

import React, { useState } from 'react';
import { Card } from 'antd';
import BankConnectionDrawer from './BankConnectionDrawer';

const BankConnectionCard = (props) => {
  const { type, name, updateBankAccountsTab } = props;
  const [visible, setVisible] = useState(false);
  const showDrawer = () => {
    setVisible(true);
  };
  const onClose = () => {
    setVisible(false);
  };
  return (
    <>
      <Card title={type} bordered={false} onClick={() => showDrawer()}>
        <p>Name: {name}</p>
      </Card>
      <BankConnectionDrawer
        updateBankAccountsTab={updateBankAccountsTab}
        name={name}
        visible={visible}
        onClose={onClose}
      />
    </>
  );
};

export default BankConnectionCard;
